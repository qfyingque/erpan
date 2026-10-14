import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
const file=new URL(process.env.NATIVE_CANDIDATE||'../operit/phone10-mobile-voice.js',import.meta.url);
function fixture(fails=false){
 const calls=[];
 const sandbox={exports:{},setTimeout(){throw Error('JS timer unavailable');},Java:{loadJar(){},type(name){
  if(name==='java.io.File')return {newInstance:()=>({getAbsolutePath:()=>'/private/read.jar'})};
  if(name.endsWith('.ToolParameter'))return {newInstance:(name,value)=>({name,value})};
  if(name.endsWith('.AITool'))return {newInstance:(name,parameters,description)=>({name,parameters,description})};
  if(name.endsWith('.StandardChatManagerTool'))return {newInstance:()=>({})};
  if(name.endsWith('.NativeHistoryRead'))return {read(reader,tool,timeout){calls.push({tool,timeout});if(fails)throw Error('private database error');return {getSuccess:()=>true,getResult:()=>({getChatId:()=> 'chosen',getMessages:()=>[{getTimestamp:()=>11,getSender:()=> 'ai',getContent:()=> 'selected normalized variant',getRoleName:()=> 'Astra'}]})};}};
  throw Error('unexpected class '+name);
 }}};
 vm.runInNewContext(fs.readFileSync(file,'utf8'),sandbox);sandbox.ensureNativeHistory=()=>{};
 return {sandbox,calls,context:{getCodeCacheDir:()=>'/private'}};
}
test('native read returns official message variants without JS callbacks or timers',async()=>{
 const f=fixture(),r=await f.sandbox.readMainMessages(f.context,'chosen');
 assert.deepEqual(JSON.parse(JSON.stringify(r)),{chatId:'chosen',messages:[{timestamp:11,sender:'ai',content:'selected normalized variant',roleName:'Astra'}]});
 assert.deepEqual(JSON.parse(JSON.stringify(f.calls)),[{tool:{name:'get_chat_messages',parameters:[{name:'chat_id',value:'chosen'},{name:'order',value:'desc'},{name:'limit',value:'64'}],description:''},timeout:5000}]);
});
test('native failure becomes safe listener error',async()=>{
 const f=fixture(true);assert.throws(()=>f.sandbox.readMainMessages(f.context,'chosen'),/^Error: OPERIT_OBSERVE_FAILED$/);
});
