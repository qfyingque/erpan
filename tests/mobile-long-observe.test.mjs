import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
function fixture(messages, after=10, processing=false) {
  const sandbox={exports:{}};
  vm.runInNewContext(fs.readFileSync(new URL('../operit/phone10-mobile-voice.js',import.meta.url),'utf8'),sandbox);
  const events=[];
  const request={version:1,id:'aaf0592b-907c-4010-9540-ef17c3744b21',nonce:'77c57434-3fdc-42d7-9c34-81d1bc186828',kind:'observe_stream',paged:true,chatId:'chosen',after};
  const env={emit:e=>{events.push(e);return true;},readMessages:()=>({chatId:'chosen',messages}),status:()=>({chatId:'chosen',isIdle:!processing,isProcessing:processing})};
  return {events,request,env,run:()=>sandbox.exports.runRequest(request,env)};
}
const ai=(timestamp,content)=>({timestamp,sender:'ai',content});
test('70k reply is delivered whole with bounded IPC frames, then next reply is fetched',()=>{
  const text='长篇。'.repeat(24000), f=fixture([ai(11,text),ai(12,'下一条。')]);
  f.run();
  assert.equal(f.events.at(-1).type,'complete');
  assert.equal(f.events.filter(e=>e.type==='chunk').map(e=>e.text).join(''),text);
  assert.ok(f.events.filter(e=>e.type==='chunk').every(e=>e.text.length<=8192));
  assert.equal(f.events.find(e=>e.type==='snapshot').cursor,11);
  f.events.length=0;f.request.after=11;f.run();
  assert.equal(f.events.filter(e=>e.type==='chunk').map(e=>e.text).join(''),'下一条。');
});
test('many small replies are paged instead of overflowing the 128 frame IPC queue',()=>{
  const f=fixture(Array.from({length:32},(_,i)=>ai(i+11,'消息'+i)));
  for(let i=0;i<32;i++){
    f.events.length=0;f.run();
    assert.equal(f.events.at(-1).type,'complete');
    assert.equal(f.events.filter(e=>e.type==='message_start').length,1);
    f.request.after=f.events.find(e=>e.type==='snapshot').cursor;
  }
  assert.equal(f.request.after,42);
});
test('history gap is explicitly reported and resumes oldest available message',()=>{
  const f=fixture(Array.from({length:32},(_,i)=>ai(i+20,'正文')));
  f.run();assert.equal(f.events.at(-1).type,'complete');
  assert.equal(f.events.find(e=>e.type==='snapshot').notice,'HISTORY_GAP');
  assert.equal(f.events.find(e=>e.type==='snapshot').cursor,20);
});
test('extreme item is explicitly skipped without truncation and next reply remains available',()=>{
  const f=fixture([ai(11,'字'.repeat(512001)),ai(12,'后续正常')]);
  f.run();assert.equal(f.events.at(-1).type,'complete');
  assert.equal(f.events.find(e=>e.type==='snapshot').notice,'MESSAGE_TOO_LARGE');
  assert.equal(f.events.filter(e=>e.type==='chunk').length,0);
  f.request.after=11;f.events.length=0;f.run();
  assert.equal(f.events.find(e=>e.type==='chunk').text,'后续正常');
});
test('completed predecessor advances cursor while latest partial stays open',()=>{
  const f=fixture([ai(11,'上一条'),ai(12,'正在写')],10,true);
  f.run();assert.equal(f.events.find(e=>e.type==='message_start').finished,true);
  f.request.after=11;f.events.length=0;f.run();
  assert.equal(f.events.find(e=>e.type==='message_start').finished,false);
  assert.equal(f.events.find(e=>e.type==='snapshot').cursor,11);
});
