import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
import test from 'node:test';
const source=fs.readFileSync(new URL('../operit/phone10-mobile-voice.js',import.meta.url),'utf8');
const base={version:1,id:'aaf0592b-907c-4010-9540-ef17c3744b21',nonce:'77c57434-3fdc-42d7-9c34-81d1bc186828',kind:'reply',chatId:'chosen',text:'test'};
function core(){const box={exports:{}};vm.runInNewContext(source,box);return box.exports.runRequest;}
test('cancel receipt waits for the original worker to release, not just cancel acknowledgement',async()=>{
  let held=true, slept=0,cancels=0;const events=[];
  await core()({...base,kind:'cancel',targetId:base.id,waitForRelease:true},{
    cancel(){cancels++;},released(){return !held;},
    async pause(){slept++;if(slept===3)held=false;},emit(e){events.push(e);return true;}
  });
  assert.equal(slept,3);assert.equal(cancels,1);
  assert.equal(events.at(-1).released,true);assert.equal(events.at(-1).type,'complete');
});
test('slow cancellation never releases a held worker or reports completion',async()=>{
  const events=[];let sleeps=0;
  await core()({...base,kind:'cancel',targetId:base.id,waitForRelease:true},{
    cancel(){},released(){return false;},async pause(){sleeps++;},
    release(){assert.fail('must not steal worker ownership');},emit(e){events.push(e);return true;}
  });
  assert.equal(events.at(-1).code,'OPERIT_CANCEL_PENDING');assert.ok(sleeps>0&&sleeps<=50);
});
test('readback after a timeout does not cancel again or send a model request',async()=>{
  const events=[];
  await core()({...base,kind:'cancel',targetId:base.id,waitForRelease:true,checkOnly:true},{
    cancel(){assert.fail('readback must not repeat cancel');},released(){return true;},
    emit(e){events.push(e);return true;}
  });
  assert.equal(events.at(-1).released,true);
});
test('normal completion is emitted only after the worker releases its chat',async()=>{
  let held=false;const events=[];
  const env={claim(){held=true;return true;},state(){return 'claimed';},mark(){},cancel(){},release(){held=false;},
    emit(e){if(e.type==='complete')assert.equal(held,false);events.push(e);return true;},
    chat:{async sendMessageStreaming(){return{chatId:base.chatId,aiResponse:'ok'};}}};
  await core()(base,env);
  assert.equal(events.at(-1)?.type,'complete');
});
test('release readback never contends for the worker release lock and recognizes process replacement',()=>{
  const records=new Map([['_epoch','1:10'],[base.id,JSON.stringify({state:'cancelled'})]]);
  let epoch=10;
  const box={exports:{},Tools:{Chat:{}},Java:{type(name){
    if(name==='android.os.Process')return{myPid:()=>1,getStartElapsedRealtime:()=>epoch};
    throw Error('readback must not acquire a file lock: '+name);
  }}};
  vm.runInNewContext(source,box);
  const env=box.host({getSharedPreferences:()=>({getString:(k,fallback)=>records.get(k)??fallback}),
    getFilesDir:()=>({getAbsolutePath:()=>'/test'})},null);
  assert.equal(env.released(base.id),false);
  records.delete(base.id);assert.equal(env.released(base.id),true);
  records.set(base.id,'{"state":"cancelled"}');epoch=11;
  assert.equal(env.released(base.id),true);
});
