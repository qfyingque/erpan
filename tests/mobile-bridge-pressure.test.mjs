import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
const source=fs.readFileSync(new URL('../operit/phone10-mobile-voice.js',import.meta.url),'utf8');

// Android is external to Node. Count objects crossing its bridge, not heap MB.
// Reintroducing the old ToolResult -> 64 message handles fails the first test.
function fixture(count=64){
  let exposed=0;const posted=[];
  const expose=x=>{exposed++;return x;};
  const snapshot={chatId:'chosen',messages:Array.from({length:count},(_,i)=>({timestamp:i+1,sender:'ai',content:'长回复🙂'.repeat(200),roleName:'Sol'}))};
  const resolver={insert(uri,values){posted.push(values.event);return expose({getQueryParameter:()=> 'accepted'});}};
  const context={getSharedPreferences:()=>expose({}),getContentResolver:()=>expose(resolver)};
  const sandbox={exports:{},Tools:{Chat:{}},Java:{type(name){
    if(name.endsWith('.ToolParameter'))return {newInstance:(name,value)=>expose({name,value})};
    if(name.endsWith('.AITool'))return {newInstance:(name,parameters)=>expose({name,parameters})};
    if(name.endsWith('.StandardChatManagerTool'))return {newInstance:()=>expose({})};
    if(name.endsWith('.NativeHistoryRead'))return {
      read:()=>expose({getSuccess:()=>true,getResult:()=>expose({getChatId:()=>snapshot.chatId,getMessages:()=>snapshot.messages.map(m=>expose({getTimestamp:()=>m.timestamp,getSender:()=>m.sender,getContent:()=>m.content,getRoleName:()=>m.roleName}))})}),
      readSnapshot:()=>structuredClone(snapshot),
      emitEvent:(ctx,uri,json)=>{assert.equal(ctx,context);posted.push(json);return true;}
    };
    if(name==='android.content.ContentValues')return {newInstance:()=>expose({put(k,v){this[k]=v;}})};
    throw Error(name);
  }}};
  vm.runInNewContext(source,sandbox);sandbox.ensureNativeHistory=()=>{};
  return {sandbox,context,snapshot,posted,count:()=>exposed};
}

test('repeated reads do not expose handles proportional to history size',()=>{
  const small=fixture(1),large=fixture(64);
  for(let i=0;i<200;i++){
    for(const f of [small,large])assert.deepEqual(JSON.parse(JSON.stringify(f.sandbox.readMainMessages(f.context,'chosen'))),f.snapshot);
  }
  assert.equal(large.count(),small.count(),'each history message is becoming an engine-owned Java handle');
});

test('streaming emits 10000 acknowledged chunks without accumulating Java handles per chunk',()=>{
  const f=fixture(),host=f.sandbox.host(f.context,'content://test');const before=f.count();
  for(let seq=0;seq<10000;seq++)assert.equal(host.emit({id:'request',seq,type:'chunk',text:'🙂你好'}),true);
  assert.equal(f.count(),before,'each chunk allocates engine-owned ContentValues/resolver/URI handles');
  assert.equal(f.posted.length,10000);
  assert.deepEqual(JSON.parse(f.posted[9999]),{id:'request',seq:9999,type:'chunk',text:'🙂你好'});
});
