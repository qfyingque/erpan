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
// O's current legacy JS runtime caps one script execution at 30 minutes.
// Match that supported long-task window; this is not an unlimited task runner.
const REPLY_TIMEOUT_MS = 30 * 60 * 1000;
const UUID = /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/;
const ERROR_CODES = ['INVALID_REQUEST','OPERIT_STREAMING_UNAVAILABLE','REQUEST_ALREADY_CLAIMED',
  'OPERIT_BUSY','JOURNAL_FAILED','JOURNAL_FULL','INVALID_OPERIT_RESPONSE','RESPONSE_TOO_LARGE',
  'OPERIT_REPLY_FAILED','OPERIT_LIST_FAILED','OPERIT_CANCEL_FAILED','OPERIT_OBSERVE_UNAVAILABLE','OPERIT_OBSERVE_FAILED'];

// Read-only snapshots through O's Chat API and the same MAIN runtime as voice turns.
function observe(request, env, emit) {
  if (typeof env.readMessages !== 'function' || typeof env.status !== 'function')
    throw Error('OPERIT_OBSERVE_UNAVAILABLE');
  if (typeof request.chatId !== 'string' || !request.chatId.trim() || request.chatId.length > 160 ||
      (request.after != null && (!Number.isSafeInteger(request.after) || request.after < 0))) throw Error('INVALID_REQUEST');
  const status = env.status(request.chatId);
  const result = env.readMessages(request.chatId, {order:'desc',limit:64});
  const finalStatus = env.status(request.chatId);
  if (!result || result.chatId !== request.chatId || !Array.isArray(result.messages) || result.messages.length > 64 ||
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
      if (messages.length === 64 && messages[0].timestamp > cursor) notice = 'HISTORY_GAP';
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
    if (messages.length === 64 && messages[0].timestamp > cursor) throw Error('RESPONSE_TOO_LARGE');
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
    if (!stopped) { env.mark(request.id, 'done'); emit('complete'); }
  } catch (error) {
    if (claimed && !stopped) env.mark(request.id, sent ? 'unknown' : 'failed');
    const message = String(error && error.message || '');
    const code = ERROR_CODES.includes(message) ? message : request.kind === 'observe' ? 'OPERIT_OBSERVE_FAILED' : 'OPERIT_REPLY_FAILED';
    emit('error', { code });
  } finally {
    if (claimed && env.release) env.release(request.id);
  }
}

// Built from operit/native/NativeHistoryRead.java. No download or credentials.
let nativeHistoryReady=false;
function ensureNativeHistory(context) {
  if(nativeHistoryReady)return;
  const file=Java.type('java.io.File').newInstance(context.getFilesDir(),'phone10-native-read-8799234c1d79d892.jar');
  if(!file.exists()){
    const bytes=Java.type('java.util.Base64').getDecoder().decode('UEsDBBQACAgIAAAAIQAAAAAAAAAAAAAAAAALAAkAY2xhc3Nlcy5kZXhVVAUAAQAAAACVl11oHNcVx8/M7sx+ylp926o/1hs5lh1Ju1IU67OOE2mNZa0l1yupwaYNs7tXu2PNzq5nZhUpTl1jGpqHPriFfBUCegglgQYMpVBDC6VfpDQPhTb0pQ8tTUge8tCWQh760P7vvbPRGi2FLvzmnDn33K9zztVcldhONPPkNL39zYcnZ96+5Sz/9s2Vr7x6++mP997TEvG/XPp9nKhORDsbk93k/+KwPUvSfpy3KUQJyL9DBiAfqER9kO9DqtwPxmQEdsjfaES/A38AfwJ/BR+BT8Bn4B/gc3BaJ1oHL4Lvg4fgQ/Ap+A84FSK6BG6CO+B74AfgZ+AzEA4TTYCvgTvgu+BH4EPwb/A41pMHBtgFL4G74GXwCvgO2AM/Br8AH4C/gX+Cz0FnlOgIOAsmwQWwDDZAFTTAbfA6eAu8Cx6CX4MPQBBxwTAElWI8pqADHAKdJOPZBXjQe0AvyZj2gwFwGBwBg+BL4Cg45ucjCU6CFDgFhsFZ8ARAaCnk51Hz5+U/3ddfgjHs69/SpC/X72vS/1BL305fD/lrfcP3GfDtp/z18d/jvr6n7evv8Dz7+oMW+0+gn/H3tdei/xy6gog8LeLRS4+JOBwWNacgeoqQYXpKyCh9mXj9RYRdhde8WGuIzonYx4Q9it0pYo8RXyKfYp8apYWM0JTIh04TYp9BmhF5kf7d8BgVspPGhFQpI3Im/fsww3mRK4XGxbr8MwQ87GcI8s8ogDdi0q76sWzmhbf/C23vfdEuR1jU5FkrwNIl7LLlkiZzeQMv9URY7CDgt17RZF3dSvTgfQ2qnexEtQyHOlT5lsCbzHAY/gr2GMF+g/Be1+R57xf7CNEslaC/0sP1efo6aq0b8/G+KUWhepLv1E5EEMW4yqVO8cDRYD8NJY/SAvTB4JNkZzR6NhBXh5LHMFZctzMddE2Pa7dEtcf1CaaJFV6l4UiHZififDSNr/IqFTSMf6GL6s/FtS6Nt3aL1uRGmKZ1jU5oYTqtpfQoDYbmqJ4J0bZ2Wu/A2FExtp2IYU3w0CI0qE9jxToxvJ8V8VdoUZwJ7FxJzKux53uWRe4UEeOqJs9vM779oE/B4wge89d3qIdn5tAvrxeU3sS3X3tt9/jyzj219iqCL4KrkDzHqoh0r8hj3M+4KvRjQnbgVDdrUiH590HaO/3ak/ZOCgyf2SB93rRN7zyp5+covLSSX3tmZSFLymVSchTIXeaPXI6CeFymsVyxVk1XGma5ka5XajYbz6RhqRteesXwzG12yXS9mrN7jRmlofE5Gvk//OfosVzJsLbNrbRh2zUP7TU7nbWLVs017fIV5lVqcDrexmnJtpmzYBmuO0fH2rTnzbJteA2HzdFgm+a1ilN7AV27cjeNbSNtGXY57Y/W12LK7hRZnXeYo6EW85JlsbJhPeOUG1Vmey1eJw965TEla3FJtrrYHnOcRt1jpRaP7haP1cJNVvQeteU9B9F51IYNiYD2PWqrvWAULMRgtMXssE0LY2Ly7VpRhGPNcMqsdRuDbdyb2RiWbQ3PtJBYu9hwHMQgvYAcW8zj011syMCfae+Z3WHFBp+nZcIT7V3XzCpbR6nyRG3VPMu009eY27C8ZY8H0jcVa04NPW3mYhU2lIbRDPX/9phHVtt5+Cp3ZTse38hBr2y17u0edH2ijatp84S5ZtHlCfdVvoP4laVcbimfXVhdWcxT/4HzMcaDQsoGqRs4kxs4k8p1Uq/nqPvGwbrtudGmbGJGschc96JllF0KF2WOGPU1tS9SYFjWLnX4cZfFRAEUBfXjsbB6bXV9bWkl+3x+PX81u7KYXaQwtxsNl1En1yqGdwUTGWXmyia+KIpyTYaGItBlDVHEbNY99Zn2tmGZpSSfMVnCA8FjpMNc22IUtI0qo0FbRCZZkaGRvpuGabESBcVSow7Kosq+anoVOuTxul+1L8IBdUhRTDDkiLIhDXqD0VvKnTuL07dTBaO4xexSajZVYjupkRQPi2mJ2hit1kpMNBQaZTRVDHe0WGHFLczjpmY3DctlI6mqaY8adTM1O3FuJOVWjNFxdDGmjNL4tDGVmTyXKUzNzJQyk9MTbHJmqjiemTGmnipsTm3OjGP0kdQ2c1xMhk7TY+OZsZnREttOfYNUTblwVBkgNaBcGAyqQXUkSLPHhTU40DtwAqiqzn0O4/OrqHQ/SXfvBt8PKcoD8FFIXGPu3Q3+MKpE9sLib78Vk/cAD/JFcC8mvwn3IX/l3xmadw7++2lsX2/a+bfsjzF5N2i1c9m85/PvTvOuH6D9+z7v27zza7R/79dp/+6vJGUbv/8HfJ3fX5SEvP/ye5CalHPx/w+Cvg/fG7/M8nH4/SiQkHPxPei+nd+d+GLEdxIfyv8CUEsHCOS1VjT0BgAA0AwAAFBLAQIUABQACAgIAAAAIQDktVY09AYAANAMAAALAAkAAAAAAAAAAAAAAAAAAABjbGFzc2VzLmRleFVUBQABAAAAAFBLBQYAAAAAAQABAEIAAAA2BwAAAAA=');
    const input=Java.type('java.io.ByteArrayInputStream').newInstance(bytes);
    const output=Java.type('java.io.FileOutputStream').newInstance(file);
    try {
      if(!file.setReadOnly())throw Error('NATIVE_HISTORY_READONLY_FAILED');
      input.transferTo(output);
    } finally {input.close();output.close();}
  }
  const digest=Java.type('java.security.MessageDigest').getInstance('SHA-256');
  const raw=Java.type('java.io.FileInputStream').newInstance(file);
  const checked=Java.type('java.security.DigestInputStream').newInstance(raw,digest);
  try{while(checked.read()!==-1){}}finally{checked.close();}
  const actual=digest.digest().map(b=>(b&255).toString(16).padStart(2,'0')).join('');
  if(actual!=='8799234c1d79d89293848e8c685979f7580d7b63e3132732a6cd6812f3519a42')throw Error('NATIVE_HISTORY_HASH_MISMATCH');
  Java.loadJar(String(file.getAbsolutePath()));
  nativeHistoryReady=true;
}

function readMainMessages(context, chatId) {
  try {
    const type = name => Java.type('com.ai.assistance.operit.' + name);
    const parameters = [['chat_id',chatId],['order','desc'],['limit','64']]
      .map(pair => type('data.model.ToolParameter').newInstance(...pair));
    const tool = type('data.model.AITool').newInstance('get_chat_messages',parameters,'');
    const reader = type('core.tools.defaultTool.standard.StandardChatManagerTool').newInstance(context);
    ensureNativeHistory(context);
    const result = Java.type('com.huigu.phone10.compat.NativeHistoryRead').read(reader,tool,5000);
    if (!result.getSuccess()) throw Error('OPERIT_OBSERVE_FAILED');
    const data = result.getResult();
    return {chatId:data.getChatId(),messages:data.getMessages().map(m => ({
      timestamp:m.getTimestamp(),sender:m.getSender(),content:m.getContent(),roleName:String(m.getRoleName() || '')
    }))};
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
        input.transferTo(output);
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
