/* METADATA
{
  "name":"phone10_mobile_voice",
  "display_name":"Phone10 独立手机语音",
  "description":"手机云端语音的本机桥：O 保持所选聊天、角色、模型及工具的所有权。",
  "enabledByDefault":false,
  "category":"Utility",
  "tools":[
    {"name":"setup","description":"安装本机语音事件工作流并检查流式接口，不发送聊天。","parameters":[]},
    {"name":"receive","description":"仅供 Phone10 授权 URI 的工作流使用。","parameters":[{"name":"event","type":"string","required":true,"description":"工作流触发参数"}]}
  ]
}
*/

const MOBILE_ACTION = 'com.huigu.phone10.mobile.OPERIT_VOICE';
const CANCEL_ACTION = 'com.huigu.phone10.mobile.OPERIT_VOICE_CANCEL';
const MOBILE_WORKFLOW = 'Phone10 独立手机语音';
// Test history window; keep reader and reconnect-gap checks in sync.
const HISTORY_LIMIT = 32;
// O's current legacy JS runtime caps one script execution at 30 minutes.
// Match that supported long-task window; this is not an unlimited task runner.
const REPLY_TIMEOUT_MS = 30 * 60 * 1000;
const UUID = /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/;
const ERROR_CODES = ['INVALID_REQUEST','OPERIT_STREAMING_UNAVAILABLE','REQUEST_ALREADY_CLAIMED',
  'OPERIT_BUSY','JOURNAL_FAILED','JOURNAL_FULL','INVALID_OPERIT_RESPONSE','RESPONSE_TOO_LARGE',
  'OPERIT_REPLY_FAILED','OPERIT_LIST_FAILED','OPERIT_CANCEL_FAILED','OPERIT_CANCEL_PENDING','OPERIT_OBSERVE_UNAVAILABLE','OPERIT_OBSERVE_FAILED'];

// Read-only snapshots through O's Chat API and the same MAIN runtime as voice turns.
function observe(request, env, emit) {
  if (typeof env.readMessages !== 'function' || typeof env.status !== 'function')
    throw Error('OPERIT_OBSERVE_UNAVAILABLE');
  if (typeof request.chatId !== 'string' || !request.chatId.trim() || request.chatId.length > 160 ||
      (request.after != null && (!Number.isSafeInteger(request.after) || request.after < 0))) throw Error('INVALID_REQUEST');
  const status = env.status(request.chatId);
  const result = env.readMessages(request.chatId, {order:'desc',limit:HISTORY_LIMIT});
  const finalStatus = env.status(request.chatId);
  if (!result || result.chatId !== request.chatId || !Array.isArray(result.messages) || result.messages.length > HISTORY_LIMIT ||
      !status || status.chatId !== request.chatId || !finalStatus || finalStatus.chatId !== request.chatId ||
      typeof status.isIdle !== 'boolean' || typeof finalStatus.isIdle !== 'boolean') throw Error('OPERIT_OBSERVE_FAILED');
  const messages = result.messages.slice().sort((a,b)=>a.timestamp-b.timestamp);
  for (const m of messages) if (!Number.isSafeInteger(m.timestamp) || m.timestamp < 0 ||
      typeof m.sender !== 'string' || typeof m.content !== 'string') throw Error('OPERIT_OBSERVE_FAILED');
  const ready = status.isIdle && finalStatus.isIdle && !status.isProcessing && !finalStatus.isProcessing;
  let cursor = request.after == null ? (messages[messages.length-1]?.timestamp || 0) : request.after;
  if (status.state === 'error' && finalStatus.state === 'error') {
    // O kept a failed/aborted partial turn: never read it later as a new completed reply.
    cursor = Math.max(cursor,messages[messages.length-1]?.timestamp || 0);
  }
  const roles = [...new Set(messages.filter(m=>m.sender==='ai' && typeof m.roleName==='string').map(m=>m.roleName.trim()).filter(n=>n && n.length<=256))];
  const roleName = m => typeof m.roleName==='string' && m.roleName.length<=256 ? m.roleName.trim() : '';
  const streaming = request.kind === 'observe_stream';
  const failed = status.state === 'error' || finalStatus.state === 'error';
  if (streaming && failed) cursor = Math.max(cursor,messages[messages.length-1]?.timestamp || 0);
  if (streaming && request.paged === true) {
    // One message per synchronous request keeps both Binder frames and the
    // native event channel bounded. Completed predecessors advance separately
    // even while O is already generating the next reply.
    let notice;
    if (!failed && request.after != null) {
      if (messages.length === HISTORY_LIMIT && messages[0].timestamp > cursor) notice = 'HISTORY_GAP';
      const candidates = messages.filter(m => m.timestamp > cursor && m.sender === 'ai' && m.content.trim());
      const m = candidates[0];
      if (m) {
        const finished = ready || candidates.length > 1;
        if (m.content.length > 512000) {
          // Do not truncate or retry this same item forever. Its text stays in O.
          notice = 'MESSAGE_TOO_LARGE';
          cursor = m.timestamp;
        } else {
          if (!emit('message_start',{timestamp:m.timestamp,finished,roleName:roleName(m)})) return;
          for (let i=0;i<m.content.length;i+=8192)
            if (!emit('chunk',{text:m.content.slice(i,i+8192)})) return;
          if (!emit('message_end')) return;
          if (finished) cursor = m.timestamp;
        }
      } else if (ready) cursor = Math.max(cursor,messages[messages.length-1]?.timestamp || 0);
    }
    if (emit('snapshot',{roleMetadata:true,roles,cursor,processing:!ready,streaming:true,paged:true,failed,...(notice?{notice}:{})})) emit('complete');
    return;
  }
  if ((ready || (streaming && !failed)) && request.after != null) {
    // Detect a missed window rather than silently skipping a backlog.
    if (messages.length === HISTORY_LIMIT && messages[0].timestamp > cursor) throw Error('RESPONSE_TOO_LARGE');
    let count = 0;
    for (const m of messages) {
      if (m.timestamp <= request.after) continue;
      if (ready) cursor = Math.max(cursor,m.timestamp);
      if (m.sender !== 'ai' || !m.content.trim()) continue;
      count += m.content.length;
      if (count > 60000) throw Error('RESPONSE_TOO_LARGE');
      if (!emit('message_start',{timestamp:m.timestamp,roleName:roleName(m),...(streaming?{finished:ready}:{})})) return;
      for (let i=0;i<m.content.length;i+=8192) if (!emit('chunk',{text:m.content.slice(i,i+8192)})) return;
      if (!emit('message_end')) return;
    }
  }
  if (emit('snapshot',{roleMetadata:true,roles,cursor,processing:!ready,...(streaming?{streaming:true,failed}:{})})) emit('complete');
  }

// Host-independent protocol core. emit is synchronous because the JS streaming
// callback is not awaited by O; ContentProvider.insert supplies backpressure/error.
function runRequest(request, env) {
  if (!['observe','observe_stream'].includes(request.kind)) return runAsyncRequest(request,env);
  let seq=0,stopped=false;
  function emit(type,data) {
    if(stopped)return false;
    try { if(env.emit({version:1,id:request.id,nonce:request.nonce,seq:seq++,type,...data})!==true)stopped=true; }
    catch(_){stopped=true;}
    return !stopped;
  }
  try {
    if(request.version!==1||!UUID.test(request.id)||!UUID.test(request.nonce))throw Error('INVALID_REQUEST');
    observe(request,env,emit);
  } catch(error) {
    const message=String(error&&error.message||'');
    emit('error',{code:ERROR_CODES.includes(message)?message:'OPERIT_OBSERVE_FAILED'});
  }
}

async function runAsyncRequest(request, env) {
  let seq = 0, stopped = false, claimed = false, sent = false, streamed = '', streamError;
  function stop() {
    if (stopped) return;
    stopped = true;
    if (sent) env.cancel(request.id);
  }
  function emit(type, data) {
    if (stopped) return false;
    try {
      if (env.emit({ version:1, id:request.id, nonce:request.nonce, seq:seq++, type, ...data }) !== true) {
        stop(); return false;
      }
      return true;
    } catch (_) { stop(); return false; }
  }
  function chunk(text) {
    if (streamed.length + text.length > 1000000) throw Error('RESPONSE_TOO_LARGE');
    streamed += text;
    for (let offset = 0; offset < text.length && !stopped; offset += 8192) emit('chunk', { text:text.slice(offset, offset + 8192) });
  }
  try {
    if (request.version !== 1 || !UUID.test(request.id) || !UUID.test(request.nonce)
        || !['reply','list','probe','cancel','observe'].includes(request.kind)) throw Error('INVALID_REQUEST');
    if (request.kind === 'cancel') {
      if (!UUID.test(request.targetId)) throw Error('INVALID_REQUEST');
      if (request.checkOnly !== true) env.cancel(request.targetId);
      if (request.waitForRelease === true) {
        // A cancellation acknowledgement is not worker termination. Never release
        // the original claim here; its own finally block remains the only owner.
        if (typeof env.released !== 'function') throw Error('OPERIT_CANCEL_FAILED');
        let released = env.released(request.targetId);
        for (let i=0; !released && i<40; i++) {
          await env.pause(100);
          released = env.released(request.targetId);
        }
        if (!released) throw Error('OPERIT_CANCEL_PENDING');
        emit('complete', {released:true}); return;
      }
      emit('complete'); return; // Compatibility with older APKs.
    }
    if (!env.chat || typeof env.chat.sendMessageStreaming !== 'function') throw Error('OPERIT_STREAMING_UNAVAILABLE');
    if (request.kind === 'probe') { emit('complete'); return; }
    if (request.kind === 'list') {
      let result;
      try { result = await env.chat.listAll(); } catch (_) { throw Error('OPERIT_LIST_FAILED'); }
      if (!result || !Array.isArray(result.chats) || result.chats.length > 2000) throw Error('OPERIT_LIST_FAILED');
      const chats = result.chats.map(c => {
        if (typeof c.id !== 'string' || c.id.length > 160 || typeof c.title !== 'string') throw Error('OPERIT_LIST_FAILED');
        return { id:c.id, title:c.title.slice(0,256) };
      });
      // Separate list frames keep every Binder transaction bounded.
      for (let i = 0; i < chats.length; i += 40) if (!emit('chats', { chats:chats.slice(i,i+40) })) return;
      emit('complete'); return;
    }
    if (typeof request.chatId !== 'string' || !request.chatId.trim() || request.chatId.length > 160
        || typeof request.text !== 'string' || !request.text.trim() || request.text.length > 60000) throw Error('INVALID_REQUEST');
    // Duplicate broadcasts do not own the active stream, including its errors.
    if (!env.claim(request)) return;
    claimed = true;
    // This round trip checks native cancellation immediately before model dispatch.
    if (!emit('accepted') || env.state(request.id) === 'cancelled') return;
    sent = true;
    const result = await env.chat.sendMessageStreaming(request.text, request.chatId, undefined, undefined, {
      runtime:'main', persist_turn:true, waifu:false, timeout_ms:REPLY_TIMEOUT_MS,
      onIntermediateResult(event) {
        try {
          if (!event || event.chatId !== request.chatId) throw Error('INVALID_OPERIT_RESPONSE');
          if (event.type === 'start') env.started(request.id);
          if (env.state(request.id) === 'cancelled') {
            stopped = true;
            // Preflight may still be waiting for a user's previous O turn.
            // Only this request's actual stream permits upstream cancellation.
            if (event.type === 'start') env.cancel(request.id);
            return;
          }
          if (stopped || streamError) return;
          if (event.type === 'chunk') {
            if (typeof event.chunk !== 'string') throw Error('INVALID_OPERIT_RESPONSE');
            chunk(event.chunk);
          }
        } catch (error) { streamError = error; env.cancel(request.id); }
      }
    });
    if (streamError) throw streamError;
    if (stopped || env.state(request.id) === 'cancelled') return;
    if (!result || result.chatId !== request.chatId || typeof result.aiResponse !== 'string') throw Error('INVALID_OPERIT_RESPONSE');
    if (!result.aiResponse.startsWith(streamed)) throw Error('INVALID_OPERIT_RESPONSE');
    chunk(result.aiResponse.slice(streamed.length));
    if (!stopped) {
      env.mark(request.id, 'done');
      // sendMessageStreaming has returned; release before advertising completion.
      if (env.release) { env.release(request.id); claimed=false; }
      emit('complete');
    }
  } catch (error) {
    if (claimed && !stopped) env.mark(request.id, sent ? 'unknown' : 'failed');
    const message = String(error && error.message || '');
    const code = ERROR_CODES.includes(message) ? message : request.kind === 'observe' ? 'OPERIT_OBSERVE_FAILED' : 'OPERIT_REPLY_FAILED';
    emit('error', { code });
  } finally {
    if (claimed && env.release) env.release(request.id);
  }
}

// Android 12 compatibility revision 20261019. Channel APIs are available since
// Android API 1; InputStream.transferTo requires API 33 on the host runtime.
function copyStreamCompat(input, output) {
  const source = Java.type('java.nio.channels.Channels').newChannel(input);
  const target = output.getChannel();
  let offset = 0;
  while (true) {
    const count = Number(target.transferFrom(source, offset, 1024 * 1024));
    if (count === 0) return;
    if (!Number.isSafeInteger(count) || count < 0) throw Error('STREAM_COPY_FAILED');
    offset += count;
  }
}

// Built from operit/native/NativeHistoryRead.java. No download or credentials.
let nativeHistoryReady=false;
function ensureNativeHistory(context) {
  if(nativeHistoryReady)return;
  // A failed older script can leave its read-only final file empty. Use a new
  // name and publish only a complete copy, preserving the old file for rollback.
  const File=Java.type('java.io.File');
  const file=File.newInstance(context.getFilesDir(),'phone10-native-read-ebd520325e38e82d-compat12.jar');
  if(!file.exists()){
    const bytes=Java.type('java.util.Base64').getDecoder().decode('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');
    const input=Java.type('java.io.ByteArrayInputStream').newInstance(bytes);
    const temporary=File.newInstance(context.getFilesDir(),'phone10-native-read-'+String(Java.type('java.util.UUID').randomUUID())+'.tmp');
    const output=Java.type('java.io.FileOutputStream').newInstance(temporary);
    try {
      if(!temporary.setReadOnly())throw Error('NATIVE_HISTORY_READONLY_FAILED');
      copyStreamCompat(input,output);
    } finally {input.close();output.close();}
    if(!temporary.renameTo(file) && !file.exists())throw Error('NATIVE_HISTORY_WRITE_FAILED');
  }
  const digest=Java.type('java.security.MessageDigest').getInstance('SHA-256');
  const raw=Java.type('java.io.FileInputStream').newInstance(file);
  const checked=Java.type('java.security.DigestInputStream').newInstance(raw,digest);
  try{while(checked.read()!==-1){}}finally{checked.close();}
  const actual=digest.digest().map(b=>(b&255).toString(16).padStart(2,'0')).join('');
  if(actual!=='ebd520325e38e82d791a6d1b2773396232ca78573d704ee242aaeede944627ec')throw Error('NATIVE_HISTORY_HASH_MISMATCH');
  Java.loadJar(String(file.getAbsolutePath()));
  nativeHistoryReady=true;
}

function readMainMessages(context, chatId) {
  try {
    const type = name => Java.type('com.ai.assistance.operit.' + name);
    const parameters = [['chat_id',chatId],['order','desc'],['limit',String(HISTORY_LIMIT)]]
      .map(pair => type('data.model.ToolParameter').newInstance(...pair));
    const tool = type('data.model.AITool').newInstance('get_chat_messages',parameters,'');
    const reader = type('core.tools.defaultTool.standard.StandardChatManagerTool').newInstance(context);
    ensureNativeHistory(context);
    return Java.type('com.huigu.phone10.compat.NativeHistoryRead').readSnapshot(reader,tool,5000);
  } catch (_) { throw Error('OPERIT_OBSERVE_FAILED'); }
}

function readMainStatus(context, chatId) {
  const holder = Java.type('com.ai.assistance.operit.api.chat.ChatRuntimeHolder').getStatic('Companion').getInstance(context);
  const runtime = holder.getCore(Java.type('com.ai.assistance.operit.api.chat.ChatRuntimeSlot').getStatic('MAIN'));
  // Java bridge converts Map to a JS object, and values to typed Java handles.
  const states = runtime.getInputProcessingStateByChatId().getValue();
  const value = states[chatId];
  const name = value == null ? 'Idle' : String(value.className || value.__javaClass || '').split('$').pop();
  if (!['Idle','Completed','Error','Processing','Connecting','Receiving','ExecutingTool','ToolProgress',
      'ProcessingToolResult','Summarizing','ExecutingPlan'].includes(name)) throw Error('OPERIT_OBSERVE_UNAVAILABLE');
  return {chatId,state:name.toLowerCase(),isIdle:name==='Idle'||name==='Completed',
    isProcessing:!['Idle','Completed','Error'].includes(name)};
}

function host(context, uri) {
  const prefs = context.getSharedPreferences('phone10_mobile_voice_claims_v1', 0);
  function locked(fn) {
    const path = String(context.getFilesDir().getAbsolutePath()) + '/phone10-mobile-voice.lock';
    const file = Java.type('java.io.RandomAccessFile').newInstance(path, 'rw');
    const channel = file.getChannel();
    let lock;
    try {
      lock = channel.tryLock();
      if (!lock) throw Error('OPERIT_BUSY');
      // Native accepted(seq=0) owns at-most-once delivery. These records only
      // coordinate live O workers and must not survive the owning process.
      const process = Java.type('android.os.Process');
      const epoch = String(process.myPid()) + ':' + String(process.getStartElapsedRealtime());
      if (String(prefs.getString('_epoch', '')) !== epoch &&
          !prefs.edit().clear().putString('_epoch', epoch).commit()) throw Error('JOURNAL_FAILED');
      return fn();
    } finally { if (lock) lock.release(); channel.close(); file.close(); }
  }
  function read(id) { const s = prefs.getString(id, null); return s ? JSON.parse(String(s)) : null; }
  function put(id, record) {
    if (!prefs.edit().putString(id, JSON.stringify(record)).commit()) throw Error('JOURNAL_FAILED');
  }
  function core() {
    const holder = Java.type('com.ai.assistance.operit.api.chat.ChatRuntimeHolder')
      .getStatic('Companion').getInstance(context);
    const slot = Java.type('com.ai.assistance.operit.api.chat.ChatRuntimeSlot').getStatic('MAIN');
    return holder.getCore(slot);
  }
  function streamToken(runtime, chatId) {
    const stream = runtime.getResponseStream(chatId);
    return stream === null ? null : Number(Java.type('java.lang.System').identityHashCode(stream));
  }
  return {
    chat:Tools.Chat,
    readMessages(chatId) { return readMainMessages(context,chatId); },
    status(chatId) { return readMainStatus(context,chatId); },
    claim(request) {
      return locked(() => {
        if (read(request.id)) return false;
        const active = read('active:' + request.chatId);
        if (active) throw Error('OPERIT_BUSY');
        const editor = prefs.edit();
        editor.putString(request.id, JSON.stringify({ state:'claimed', chatId:request.chatId }));
        editor.putString('active:' + request.chatId, JSON.stringify({ id:request.id }));
        if (!editor.commit()) throw Error('JOURNAL_FAILED');
        return true;
      });
    },
    state(id) { return read(id)?.state; },
    released(id) {
      // SharedPreferences applies a commit atomically in this O process. Polling
      // must not take the file lock the original worker needs in order to release.
      const process=Java.type('android.os.Process');
      const epoch=String(process.myPid())+':'+String(process.getStartElapsedRealtime());
      return String(prefs.getString('_epoch','')) !== epoch || read(id) == null;
    },
    pause(ms) { Java.type('java.lang.Thread').sleep(ms); },
    started(id) {
      locked(() => {
        const r = read(id);
        if (r && read('active:' + r.chatId)?.id === id && r.streamToken === undefined) {
          put(id, { ...r, streamToken:streamToken(core(), r.chatId) });
        }
      });
    },
    mark(id, state) { locked(() => { const r = read(id); if (r && r.state !== 'cancelled') put(id,{ ...r,state }); }); },
    release(id) {
      locked(() => {
        const r = read(id);
        if (r && read('active:' + r.chatId)?.id === id) {
          if (!prefs.edit().remove('active:' + r.chatId).remove(id).commit()) throw Error('JOURNAL_FAILED');
        }
      });
    },
    cancel(id) {
      locked(() => {
        const r = read(id);
        if (!r || read('active:' + r.chatId)?.id !== id) return;
        put(id, { ...r, state:'cancelled' });
        if (r.streamToken === undefined || r.streamToken === null) return;
        try {
          const runtime = core();
          // O exposes chat-scoped cancellation only. Skip a replaced/finished
          // stream; its internal cancel captures activeTurnId before dispatch.
          if (streamToken(runtime, r.chatId) === r.streamToken) runtime.cancelMessage(r.chatId);
        } catch (_) { throw Error('OPERIT_CANCEL_FAILED'); }
      });
    },
    emit(event) {
      ensureNativeHistory(context);
      return Java.type('com.huigu.phone10.compat.NativeHistoryRead').emitEvent(context,uri,JSON.stringify(event));
    }
  };
}

async function setup() {
  if (!Tools.Chat || typeof Tools.Chat.sendMessageStreaming !== 'function') throw Error('OPERIT_STREAMING_UNAVAILABLE');
  const workflows = (await Tools.Workflow.getAll()).workflows;
  const ids = [];
  // O serializes each workflow. Cancellation requires its own runnable workflow.
  for (const [name, action] of [[MOBILE_WORKFLOW, MOBILE_ACTION], [MOBILE_WORKFLOW + ' · 取消', CANCEL_ACTION]]) {
    const nodes = [
      { id:'mobile_voice_event',type:'trigger',name:'手机语音请求',triggerType:'intent',triggerConfig:{action},position:{x:100,y:100} },
      { id:'mobile_voice_receive',type:'execute',name:'O 聊天流',actionType:'phone10_mobile_voice:receive',actionConfig:{event:{nodeId:'mobile_voice_event'}},position:{x:420,y:100} }
    ];
    const connections = [{ id:'mobile_voice_link',sourceNodeId:'mobile_voice_event',targetNodeId:'mobile_voice_receive' }];
    const matches = workflows.filter(w => w.name === name);
    if (matches.length > 1) throw Error('DUPLICATE_MOBILE_VOICE_WORKFLOW');
    const workflow = matches.length
      ? await Tools.Workflow.update(matches[0].id, { nodes,connections,enabled:true })
      : await Tools.Workflow.create(name, '独立手机云端语音的本机 IPC 请求。', nodes, connections, true);
    ids.push(workflow.id);
  }
  return { status:'SETUP_READY',workflowId:ids[0],cancelWorkflowId:ids[1],modelCalls:0 };
}

function readRequest(context, uri) {
  const stream = context.getContentResolver().openInputStream(uri);
  const scanner = Java.type('java.util.Scanner').newInstance(stream, 'UTF-8').useDelimiter('\\A');
  let request;
  try {
    const json = String(scanner.next());
    if (json.length > 262144) throw Error('INVALID_REQUEST');
    request = JSON.parse(json);
  } finally { scanner.close(); }
  if (String(uri.getLastPathSegment()) !== request.id) throw Error('INVALID_REQUEST');
  if (request.version !== 1 || !UUID.test(request.id) || !UUID.test(request.nonce)) throw Error('INVALID_REQUEST');
  return request;
}

function parseTrigger(params) {
  const trigger = JSON.parse(params.event);
  if (typeof trigger.uri !== 'string' || !/^content:\/\/com\.huigu\.phone10\.mobile\.operit\/request\/[a-f0-9-]{36}$/.test(trigger.uri)) throw Error('INVALID_REQUEST');
  return trigger;
}

function loadWorker(context) {
  try {
    // The companion APK already builds this helper from tested Java source.
    // Copy only its installed assets; no download, new service or transcript file.
    const assets = context.createPackageContext('com.huigu.phone10.mobile', 0).getAssets();
    const scanner = Java.type('java.util.Scanner').newInstance(assets.open('phone10-operit-worker.sha256'), 'UTF-8');
    let hash;
    try { hash = String(scanner.next()).trim(); } finally { scanner.close(); }
    if (!/^[a-f0-9]{64}$/.test(hash)) throw Error('WORKER_LOAD');
    const File = Java.type('java.io.File');
    const dir = context.getDir('phone10-worker', 0);
    const jar = File.newInstance(dir, 'worker-' + hash + '.jar');
    if (!jar.exists()) {
      const temporary = File.newInstance(dir, 'worker-' + String(Java.type('java.util.UUID').randomUUID()) + '.tmp');
      const input = assets.open('phone10-operit-worker.jar');
      const output = Java.type('java.io.FileOutputStream').newInstance(temporary);
      try {
        if (!temporary.setReadOnly()) throw Error('WORKER_LOAD');
        copyStreamCompat(input,output);
      } finally { input.close(); output.close(); }
      // Path is Iterable; O's bridge recursively serializes it and overflows.
      // File.renameTo returns a boolean and stays inside the same private dir.
      // Another engine may have loaded the same installed helper meanwhile.
      if (!temporary.renameTo(jar) && !jar.exists()) throw Error('WORKER_LOAD');
    }
    Java.loadJar(String(jar.getAbsolutePath()));
    return Java.type('com.huigu.phone10.mobile.OperitRequestWorker').newInstance();
  } catch (_) { throw Error('WORKER_LOAD'); }
}

function startWorker(context, trigger, request) {
  const companion = name => Java.type('com.ai.assistance.operit.' + name).getStatic('Companion');
  const handler = companion('core.tools.AIToolHandler').getInstance(context);
  const packages = companion('core.tools.packTool.PackageManager').getInstance(context, handler);
  // executeScript belongs to JsToolManager, not PackageManager, in O 1.12.2.
  const manager = companion('core.tools.javascript.JsToolManager').getInstance(context, packages);
  loadWorker(context).start(context, manager, trigger.uri, request.id, request.nonce);
}

function receive(params) {
  const trigger = parseTrigger(params);
  const context = Java.getApplicationContext();
  const uri = Java.type('android.net.Uri').parse(trigger.uri);
  const request = readRequest(context, uri);
  if (request.kind !== 'reply') return work(params);
  // The existing phone inbox owns dispatch permission across all O engines.
  // A duplicate cannot launch another worker or fail the active owner's stream.
  if (!host(context, uri).emit({version:1,id:request.id,nonce:request.nonce,type:'worker_claim'}))
    return {status:'NOT_DISPATCHED'};
  try { startWorker(context, trigger, request); }
  catch (_) {
    host(context, uri).emit({version:1, id:request.id, nonce:request.nonce, type:'worker_error'});
    return {status:'DISPATCH_FAILED'};
  }
  // Finish the Android broadcast now. The same O runtime owns the actual turn.
  return {status:'DISPATCHED'};
}

function work(params) {
  const trigger = parseTrigger(params);
  const context = Java.getApplicationContext();
  const uri = Java.type('android.net.Uri').parse(trigger.uri);
  const request = readRequest(context, uri);
  const result=runRequest(request,host(context,uri));
  const finish=()=>{return {status:'HANDLED'};};
  return result&&typeof result.then==='function'?result.then(finish):finish();
}

if (typeof exports !== 'undefined') {
  exports.runRequest = runRequest;
  exports.setup = async function() { const result = await setup(); complete(result); return result; };
  exports.receive = function(params) { const result=receive(params); const finish=value=>{complete(value);return value;};return result&&typeof result.then==='function'?result.then(finish):finish(result); };
  exports.work = function(params) { const result=work(params); const finish=value=>{complete(value);return value;};return result&&typeof result.then==='function'?result.then(finish):finish(result); };
}
