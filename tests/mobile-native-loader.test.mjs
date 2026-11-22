import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
import crypto from 'node:crypto';
const source=fs.readFileSync(new URL('../operit/phone10-mobile-voice.js',import.meta.url),'utf8');
const jar=fs.readFileSync(new URL('../operit/native/native-read.jar',import.meta.url));
function fixture(existing){
 let bytes=existing,readonly=false,loads=0,writes=0;
 const file={exists:()=>bytes!==undefined,setReadOnly(){readonly=true;return true;},renameTo(){return true;},getAbsolutePath:()=>'/private/files/helper.jar'};
 const sandbox={exports:{},Java:{type(name){
  if(name==='java.io.File')return {newInstance:(dir)=>{assert.equal(dir,'/private/files');return file;}};
  if(name==='java.util.Base64')return {getDecoder:()=>({decode:text=>Buffer.from(text,'base64')})};
  if(name==='java.util.UUID')return {randomUUID:()=> 'test-unique'};
  if(name==='java.io.ByteArrayInputStream')return {newInstance:data=>({data,close(){}})};
  if(name==='java.nio.channels.Channels')return {newChannel:input=>input};
  if(name==='java.io.FileOutputStream')return {newInstance:()=>({getChannel:()=>({transferFrom(input,offset){if(offset)return 0;assert.equal(readonly,true);writes++;bytes=Buffer.from(input.data);return bytes.length;}}),close(){}})};
  if(name==='java.security.MessageDigest')return {getInstance:()=>({digest:()=>Array.from(crypto.createHash('sha256').update(bytes).digest())})};
  if(name==='java.io.FileInputStream')return {newInstance:()=>({})};
  if(name==='java.security.DigestInputStream')return {newInstance:()=>({read:()=>-1,close(){}})};
  throw Error(name);
 },loadJar(path){assert.equal(path,'/private/files/helper.jar');loads++;}}};
 vm.runInNewContext(source,sandbox);
 return {run:()=>sandbox.ensureNativeHistory({getFilesDir:()=>'/private/files'}),stats:()=>({bytes,loads,writes})};
}
test('fresh install extracts the exact compiled helper read-only and reuses it in this runtime',()=>{
 const f=fixture();f.run();f.run();assert.deepEqual(f.stats(),{bytes:jar,loads:1,writes:1});
});
test('a new plugin runtime verifies and loads the persisted helper without rewriting it',()=>{
 const f=fixture(jar);f.run();assert.deepEqual(f.stats(),{bytes:jar,loads:1,writes:0});
});
test('changed helper fails before loading code and preserves the file for inspection',()=>{
 const bad=Buffer.from('invalid'),f=fixture(bad);assert.throws(f.run,/HASH_MISMATCH/);
 assert.deepEqual(f.stats(),{bytes:bad,loads:0,writes:0});
});
