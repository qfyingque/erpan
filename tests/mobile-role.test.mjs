import { createRequire } from 'node:module';
import test from 'node:test';
import assert from 'node:assert/strict';
const {runRequest}=createRequire(import.meta.url)('../operit/phone10-mobile-voice.js');
function snapshot(messages) {
  const events=[];
  runRequest({version:1,id:'00000000-0000-4000-8000-000000000001',nonce:'00000000-0000-4000-8000-000000000002',kind:'observe_stream',chatId:'group',after:1,paged:true}, {
    status:()=>({chatId:'group',isIdle:true,isProcessing:false}),
    readMessages:()=>({chatId:'group',messages}),emit:e=>{events.push(e);return true;}
  });
  return events;
}
test('each observed message carries its actual role, separately from text',()=>{
  const events=snapshot([{timestamp:2,sender:'ai',content:'你好。',roleName:'Astra'}]);
  assert.equal(events.find(e=>e.type==='message_start').roleName,'Astra');
  assert.equal(events.find(e=>e.type==='snapshot').roleMetadata,true);
});
test('legacy blank role is explicit, never guessed from dialogue',()=>{
  const events=snapshot([{timestamp:2,sender:'ai',content:'Sol：你好。'}]);
  assert.equal(events.find(e=>e.type==='message_start').roleName,'');
});
