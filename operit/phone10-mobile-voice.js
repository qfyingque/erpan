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
const UUID = /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/;
const ERROR_CODES = ['INVALID_REQUEST','OPERIT_STREAMING_UNAVAILABLE','REQUEST_ALREADY_CLAIMED',
  'OPERIT_BUSY','JOURNAL_FAILED','JOURNAL_FULL','INVALID_OPERIT_RESPONSE','RESPONSE_TOO_LARGE',
  'OPERIT_REPLY_FAILED','OPERIT_LIST_FAILED','OPERIT_CANCEL_FAILED'];

// Host-independent protocol core. emit is synchronous because the JS streaming
// callback is not awaited by O; ContentProvider.insert supplies backpressure/error.
async function runRequest(request, env) {
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
        || !['reply','list','probe','cancel'].includes(request.kind)) throw Error('INVALID_REQUEST');
    if (request.kind === 'cancel') {
      if (!UUID.test(request.targetId)) throw Error('INVALID_REQUEST');
      env.cancel(request.targetId);
      emit('complete'); return;
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
      runtime:'main', persist_turn:true, waifu:false, timeout_ms:120000,
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
    if (!stopped) { env.mark(request.id, 'done'); emit('complete'); }
  } catch (error) {
    if (claimed && !stopped) env.mark(request.id, sent ? 'unknown' : 'failed');
    const message = String(error && error.message || '');
    const code = ERROR_CODES.includes(message) ? message : 'OPERIT_REPLY_FAILED';
    emit('error', { code });
  } finally {
    if (claimed && env.release) env.release(request.id);
  }
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
      const values = Java.type('android.content.ContentValues').newInstance();
      values.put('event', JSON.stringify(event));
      const result = context.getContentResolver().insert(uri, values);
      return result !== null && String(result.getQueryParameter('status')) === 'accepted';
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

async function receive(params) {
  const trigger = JSON.parse(params.event);
  if (typeof trigger.uri !== 'string' || !/^content:\/\/com\.huigu\.phone10\.mobile\.operit\/request\/[a-f0-9-]{36}$/.test(trigger.uri)) throw Error('INVALID_REQUEST');
  const context = Java.getApplicationContext();
  const uri = Java.type('android.net.Uri').parse(trigger.uri);
  const stream = context.getContentResolver().openInputStream(uri);
  const scanner = Java.type('java.util.Scanner').newInstance(stream, 'UTF-8').useDelimiter('\\A');
  let request;
  try {
    const json = String(scanner.next());
    if (json.length > 262144) throw Error('INVALID_REQUEST');
    request = JSON.parse(json);
  } finally { scanner.close(); }
  if (String(uri.getLastPathSegment()) !== request.id) throw Error('INVALID_REQUEST');
  await runRequest(request, host(context,uri));
  // Tool result never echoes chat text, credentials or raw upstream errors.
  return { status:'HANDLED' };
}

if (typeof exports !== 'undefined') {
  exports.runRequest = runRequest;
  exports.setup = async function() { const result = await setup(); complete(result); return result; };
  exports.receive = async function(params) { const result = await receive(params); complete(result); return result; };
}
