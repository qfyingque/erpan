import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
import test from 'node:test';

const source = new URL(process.env.MOBILE_OPERIT_SOURCE || '../operit/phone10-mobile-voice.js', import.meta.url);
const hasWorker = fs.readFileSync(source, 'utf8').includes('function loadWorker(');

test('worker bootstrap copies assets with CopyOption interface compatible arguments', { skip: !hasWorker }, () => {
  const files = new Set(), calls = [], hash = 'a'.repeat(64);
  const file = path => ({ exists: () => files.has(path), toPath: () => path,
    getAbsolutePath: () => path, setReadOnly() { calls.push('readonly'); return true; } });
  const worker = {};
  const sandbox = { exports:{}, Java:{ loadJar(path) { assert.ok(files.has(path)); calls.push('load'); }, type(name) {
    if (name === 'java.util.Scanner') return {newInstance: () => ({next: () => hash, close(){}})};
    if (name === 'java.util.UUID') return {randomUUID: () => 'unique-temp'};
    if (name === 'java.io.File') return {
      newInstance: (dir, name) => file(dir + '/' + name),
      createTempFile() { files.add('/worker/precreated.tmp'); return file('/worker/precreated.tmp'); }
    };
    if (name === 'java.nio.file.Files') return {
      copy(input, target, options) {
        // O serializes enum results as strings. CopyOption is an interface, so
        // its converter cannot infer StandardCopyOption from REPLACE_EXISTING.
        assert.equal(options.length, 0, 'CopyOption interface cannot accept string enum values');
        assert.ok(!files.has(target), 'copy without replacement needs an absent target');
        files.add(target); calls.push('copy');
      },
      move(from, to, options) {
        assert.equal(options.length, 0); assert.ok(files.has(from));
        assert.ok(calls.includes('readonly')); files.delete(from); files.add(to); calls.push('move');
      }
    };
    if (name.endsWith('.OperitRequestWorker')) return {newInstance: () => worker};
    throw Error('unexpected native type ' + name);
  }}};
  vm.runInNewContext(fs.readFileSync(source, 'utf8'), sandbox);
  const context = {createPackageContext: () => ({getAssets: () => ({open: () => ({close(){}})})}), getDir: () => '/worker'};
  assert.equal(sandbox.loadWorker(context), worker);
  assert.equal(sandbox.loadWorker(context), worker);
  assert.deepEqual(calls, ['copy','readonly','move','load','load']);
  sandbox.Java.loadJar = () => { throw Error('private raw diagnostic'); };
  assert.throws(() => sandbox.loadWorker(context), /^Error: WORKER_LOAD$/);
});

function core() {
  const sandbox = { exports: {} };
  vm.runInNewContext(fs.readFileSync(source, 'utf8'), sandbox);
  return sandbox.exports.runRequest;
}
const request = { version: 1, id: 'aaf0592b-907c-4010-9540-ef17c3744b21', nonce: '77c57434-3fdc-42d7-9c34-81d1bc186828', kind: 'reply', chatId: 'chosen-chat', text: 'hello' };
function fixture() {
  const records = new Map(), events = [], calls = [];
  const env = {
    claim(r) { if (records.has(r.id)) return false; records.set(r.id, { state: 'claimed', chatId: r.chatId }); return true; },
    state(id) { return records.get(id)?.state; },
    started() {},
    mark(id, state) { records.get(id).state = state; },
    emit(event) { events.push(event); return true; },
    cancel(id) { calls.push(['cancel', id]); if (records.has(id)) records.get(id).state = 'cancelled'; },
    chat: {
      async listAll() { calls.push(['list']); return { chats: [{ id: 'chosen-chat', title: 'Selected' }] }; },
      async sendMessageStreaming(text, chatId, role, sender, options) {
        calls.push(['send', text, chatId, role, sender, options.runtime, options.persist_turn, options.waifu]);
        options.onIntermediateResult({ type: 'start', chatId });
        options.onIntermediateResult({ type: 'chunk', chatId, chunk: 'Hello ' });
        options.onIntermediateResult({ type: 'chunk', chatId, chunk: 'world.' });
        return { chatId, aiResponse: 'Hello world.' };
      }
    }
  };
  return { env, calls, events, records };
}

test('normal O entry streams ordered deltas and completes without repeating full text', async () => {
  const f = fixture(); await core()(request, f.env);
  assert.deepEqual(f.calls[0], ['send', 'hello', 'chosen-chat', undefined, undefined, 'main', true, false]);
  assert.equal(f.events.filter(e => e.type === 'chunk').map(e => e.text).join(''), 'Hello world.');
  assert.equal(f.events.at(-1).type, 'complete');
  assert.equal(f.events.at(-1).text, undefined);
  assert.deepEqual(f.events.map(e => e.seq), f.events.map((_, i) => i));
  assert.ok(f.events.every(e => e.id === request.id && e.nonce === request.nonce));
});

test('list and probe invoke no model and return only chat identity/title', async () => {
  for (const kind of ['list', 'probe']) {
    const f = fixture(); await core()({ ...request, kind }, f.env);
    assert.equal(f.calls.filter(c => c[0] === 'send').length, 0);
    assert.equal(f.events.at(-1).type, 'complete');
    if (kind === 'list') assert.equal(f.events.find(e => e.type === 'chats').chats[0].id, 'chosen-chat');
  }
});

test('duplicate and unknown outcomes never replay a message', async () => {
  const f = fixture();
  f.env.chat.sendMessageStreaming = async () => { f.calls.push(['send']); throw Error('secret key sk-private'); };
  await core()(request, f.env);
  const eventCount = f.events.length;
  await core()(request, f.env);
  assert.equal(f.calls.filter(c => c[0] === 'send').length, 1);
  assert.equal(f.events.length, eventCount, 'duplicate must not send a competing error into the original stream');
  assert.equal(f.events.at(-1).code, 'OPERIT_REPLY_FAILED');
  assert.equal(JSON.stringify(f.events).includes('sk-private'), false);
});

test('duplicate broadcast cannot abort the active owner with an out-of-sequence error', async () => {
  const f = fixture(); let release;
  const model = new Promise(resolve => { release = resolve; });
  f.env.chat.sendMessageStreaming = async (_text, chatId) => {
    f.calls.push(['send']); await model; return { chatId, aiResponse:'好。' };
  };
  const run = core(); const original = run(request, f.env);
  try {
    await run(request, f.env);
    assert.equal(f.events.length, 1, 'only original accepted should be emitted before the reply');
  } finally { release(); await original; }
  assert.equal(f.calls.filter(c => c[0] === 'send').length, 1);
  assert.equal(f.events.at(-1).type, 'complete');
});

test('native cancellation before dispatch prevents model send', async () => {
  const f = fixture(); f.env.emit = event => { f.events.push(event); return false; };
  await core()(request, f.env);
  assert.equal(f.calls.filter(c => c[0] === 'send').length, 0);
  assert.equal(f.calls.filter(c => c[0] === 'cancel').length, 0, 'unclaimed native request must not cancel another O turn');
});

test('cancellation during stream suppresses subsequent callbacks and final completion', async () => {
  const f = fixture();
  f.env.emit = event => { f.events.push(event); return event.type !== 'chunk'; };
  await core()(request, f.env);
  assert.equal(f.events.filter(e => e.type === 'chunk').length, 1);
  assert.equal(f.events.filter(e => e.type === 'complete').length, 0);
  assert.equal(f.calls.filter(c => c[0] === 'cancel').length, 1);
});

test('separate cancel operation targets original request without sending', async () => {
  const f = fixture(); f.env.claim(request);
  await core()({ ...request, kind: 'cancel', targetId: request.id }, f.env);
  assert.deepEqual(f.calls, [['cancel', request.id]]);
});

test('missing streaming capability and mismatched response fail clearly', async () => {
  const f = fixture(); delete f.env.chat.sendMessageStreaming;
  await core()(request, f.env);
  assert.equal(f.events.at(-1).code, 'OPERIT_STREAMING_UNAVAILABLE');
  const g = fixture(); g.env.chat.sendMessageStreaming = async () => ({ chatId: 'another', aiResponse: 'wrong' });
  await core()(request, g.env);
  assert.equal(g.events.at(-1).code, 'INVALID_OPERIT_RESPONSE');
});

test('final response supplies missing suffix only and conflicting final never duplicates speech', async () => {
  const f = fixture(); f.env.chat.sendMessageStreaming = async (_, chatId, _r, _s, options) => {
    options.onIntermediateResult({ type: 'chunk', chatId, chunk: 'Hello' });
    return { chatId, aiResponse: 'Hello world' };
  };
  await core()(request, f.env);
  assert.equal(f.events.filter(e => e.type === 'chunk').map(e => e.text).join(''), 'Hello world');
});

test('setup uses separate reply and cancellation workflows because O rejects concurrent workflow runs', async () => {
  const created = [];
  const sandbox = { exports: {}, complete() {}, Tools: {
    Chat: { sendMessageStreaming() { throw Error('must never send during setup'); } },
    Workflow: {
      async getAll() { return { workflows: [] }; },
      async create(name, description, nodes) { created.push({ name, nodes }); return { id: name }; }
    }
  } };
  vm.runInNewContext(fs.readFileSync(source, 'utf8'), sandbox);
  await sandbox.exports.setup();
  assert.deepEqual(created.map(w => w.nodes[0].triggerConfig.action), [
    'com.huigu.phone10.mobile.OPERIT_VOICE', 'com.huigu.phone10.mobile.OPERIT_VOICE_CANCEL'
  ]);
});

function hostFixture() {
  const records = new Map(); let epoch = 1; let stream = { token:1 }; const cancelledChats = [];
  const prefs = { getString(k, fallback) { return records.get(k) ?? fallback; },
    getAll() { return { size: () => records.size }; }, edit() {
      const edits = []; const editor = {
        putString(k,v) { edits.push(() => records.set(k,v)); return editor; },
        remove(k) { edits.push(() => records.delete(k)); return editor; },
        clear() { edits.push(() => records.clear()); return editor; },
        commit() { edits.forEach(fn => fn()); return true; }
      }; return editor;
    } };
  const sandbox = { exports:{}, Tools:{Chat:{}}, Java:{type(name) {
    if (name === 'android.os.Process') return { myPid: () => 321, getStartElapsedRealtime: () => epoch };
    if (name === 'java.lang.System') return { identityHashCode: value => value.token };
    if (name === 'java.io.RandomAccessFile') return {newInstance: () => ({getChannel: () => ({tryLock: () => ({release(){}}), close(){}}), close(){}})};
    if (name.endsWith('ChatRuntimeHolder')) return {getStatic: () => ({getInstance: () => ({getCore: () => ({getResponseStream: () => stream, cancelMessage: id => cancelledChats.push(id)})})})};
    if (name.endsWith('ChatRuntimeSlot')) return {getStatic: () => 'MAIN'};
    throw Error('unexpected Java access ' + name);
  }} };
  vm.runInNewContext(fs.readFileSync(source, 'utf8'), sandbox);
  const context = {getSharedPreferences: () => prefs, getFilesDir: () => ({getAbsolutePath: () => '/test'})};
  return { records, cancelledChats, restart() { epoch++; }, replaceStream() { stream = {token:stream.token + 1}; }, host() { return sandbox.host(context, null); } };
}

test('O process restart releases stale active locks and never cancels a previous process claim', () => {
  const f = hostFixture(); const old = f.host(); assert.equal(old.claim(request), true);
  f.restart(); const next = f.host();
  assert.equal(next.claim({...request, id:'baf0592b-907c-4010-9540-ef17c3744b21'}), true);
  old.cancel(request.id);
  assert.deepEqual(f.cancelledChats, []);
});

test('completed claims are bounded by active work and old cancel cannot hit the next turn', () => {
  const f = hostFixture(); const env = f.host();
  for (let i = 0; i < 8200; i++) {
    const id = i.toString(16).padStart(8,'0') + '-907c-4010-9540-ef17c3744b21';
    assert.equal(env.claim({...request, id}), true); env.release(id);
  }
  assert.ok(f.records.size <= 1);
  env.claim(request); env.release(request.id);
  env.claim({...request, id:'baf0592b-907c-4010-9540-ef17c3744b21'});
  env.cancel(request.id);
  assert.deepEqual(f.cancelledChats, []);
});

test('cancel during send preflight waits for this request stream and does not cancel the prior O turn', async () => {
  const f = hostFixture(); const env = f.host();
  env.claim(request);
  env.cancel(request.id);
  assert.deepEqual(f.cancelledChats, []);
  env.started(request.id);
  env.cancel(request.id);
  assert.deepEqual(f.cancelledChats, ['chosen-chat']);
});

test('cancel skips a stream O replaced outside Phone10', () => {
  const f = hostFixture(); const env = f.host(); env.claim(request); env.started(request.id);
  f.replaceStream(); env.cancel(request.id);
  assert.deepEqual(f.cancelledChats, []);
});

test('cancel racing O asynchronous preflight is retried only after own start event', async () => {
  const f = fixture(); let starts = 0;
  f.env.started = () => starts++;
  f.env.chat.sendMessageStreaming = async (_text, chatId, _r, _s, options) => {
    f.records.get(request.id).state = 'cancelled';
    options.onIntermediateResult({type:'start',chatId});
    return {chatId,aiResponse:''};
  };
  await core()(request,f.env);
  assert.equal(starts,1);
  assert.deepEqual(f.calls,[['cancel',request.id]]);
  assert.equal(f.events.filter(e => e.type === 'complete').length,0);
});

test('broadcast returns before a slow short reply and detached work still delivers exactly once', { skip: !hasWorker }, async () => {
  const f = fixture(); const queued = []; const completions = [];
  let releaseModel;
  const model = new Promise(resolve => { releaseModel = resolve; });
  f.env.chat.sendMessageStreaming = async (_text, chatId, _r, _s, options) => {
    f.calls.push(['send']); await model;
    options.onIntermediateResult({ type:'chunk', chatId, chunk:'好。' });
    return { chatId, aiResponse:'好。' };
  };
  const sandbox = { exports:{}, complete: value => completions.push(value), Java:{
    getApplicationContext: () => ({}),
    type(name) {
      if (name === 'android.net.Uri') return { parse: () => ({ getLastPathSegment: () => request.id }) };
      throw Error('Unexpected native access: ' + name);
    }
  } };
  vm.runInNewContext(fs.readFileSync(source, 'utf8'), sandbox);
  sandbox.readRequest = () => request;
  sandbox.host = () => f.env;
  sandbox.startWorker = (_context, trigger) => queued.push(trigger);
  // Old receive reads directly from the provider; emulate that same external boundary.
  sandbox.Java.getApplicationContext = () => ({ getContentResolver: () => ({ openInputStream: () => ({}) }) });
  const nativeType = sandbox.Java.type;
  sandbox.Java.type = name => name === 'java.util.Scanner'
    ? { newInstance: () => ({ useDelimiter() { return this; }, next: () => JSON.stringify(request), close() {} }) }
    : nativeType(name);
  const trigger = { uri:'content://com.huigu.phone10.mobile.operit/request/' + request.id };
  let returned = false;
  const receipt = sandbox.exports.receive({ event:JSON.stringify(trigger) }).then(() => { returned = true; });
  try {
    await new Promise(setImmediate);
    assert.equal(returned, true, 'broadcast must finish while the model is still pending');
    assert.equal(completions[0].status, 'DISPATCHED');
    assert.equal(f.calls.length, 0);
    assert.equal(queued.length, 1);
    const work = sandbox.exports.work({ event:JSON.stringify(queued[0]) });
    releaseModel(); await work;
    assert.equal(f.calls.filter(c => c[0] === 'send').length, 1);
    assert.equal(f.events.filter(e => e.type === 'chunk').map(e => e.text).join(''), '好。');
    assert.equal(f.events.at(-1).type, 'complete');
  } finally { releaseModel(); await receipt; }
});
