import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
function run(count,after,kind='observe_stream'){
 const box={exports:{}};vm.runInNewContext(fs.readFileSync(new URL('../operit/phone10-mobile-voice.js',import.meta.url),'utf8'),box);
 const events=[],reads=[];
 box.exports.runRequest({version:1,id:'aaf0592b-907c-4010-9540-ef17c3744b21',nonce:'77c57434-3fdc-42d7-9c34-81d1bc186828',kind,paged:true,chatId:'chosen',after},{
 status:()=>({chatId:'chosen',isIdle:true,isProcessing:false}),
 readMessages:(id,options)=>{reads.push(options.limit);return {chatId:id,messages:Array.from({length:count},(_,i)=>({timestamp:100+i,sender:'ai',roleName:i%2?'Astra':'Sol',content:'回复'+i}))};},
 emit:e=>{events.push(e);return true;}});return {events,reads};
}
test('reconnect at retained cursor delivers next role without replay or gap',()=>{
 const {events,reads}=run(32,100);
 assert.deepEqual(reads,[32]);
 assert.equal(events.find(e=>e.type==='message_start').roleName,'Astra');
 assert.equal(events.find(e=>e.type==='message_start').timestamp,101);
 assert.equal(events.find(e=>e.type==='snapshot').notice,undefined);
 assert.equal(events.find(e=>e.type==='snapshot').cursor,101);
});
test('oversized host results fail visibly at 33 messages',()=>{
 const {events}=run(33,100);
 assert.equal(events.at(-1).type,'error');
 assert.equal(events.filter(e=>e.type==='chunk').length,0);
});
