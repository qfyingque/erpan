import java.lang.reflect.*;
import java.util.*;
import kotlin.coroutines.Continuation;
import com.huigu.phone10.compat.NativeHistoryRead;

public class NativeBoundaryTest {
  public static class Reader {
    final Object result;
    Reader(Object result){this.result=result;}
    public Object getChatMessages(String tool,Continuation<Object> c){return result;}
  }
  public static class Result {
    final boolean success; final Data data;
    Result(boolean success,Data data){this.success=success;this.data=data;}
    public boolean getSuccess(){return success;}
    public Data getResult(){return data;}
  }
  public static class Data {
    final int count;
    Data(int count){this.count=count;}
    public String getChatId(){return "chosen";}
    public List<Message> getMessages(){List<Message> l=new ArrayList<>();for(int i=0;i<count;i++)l.add(new Message(i));return l;}
  }
  public static class Message {
    final int i; Message(int i){this.i=i;}
    public long getTimestamp(){return 100L+i;}
    public String getSender(){return "ai";}
    public String getContent(){return "selected variant \"你好\"\n🙂";}
    public String getRoleName(){return i==0?null:"Sol";}
  }
  static Object snapshot(Object result)throws Exception {
    try{return NativeHistoryRead.class.getMethod("readSnapshot",Object.class,Object.class,long.class).invoke(null,new Reader(result),"tool",1000L);}
    catch(InvocationTargetException e){throw (Exception)e.getCause();}
  }
  static void pure(Object o){
    if(o==null||o instanceof String||o instanceof Number||o instanceof Boolean)return;
    if(o instanceof Map){for(Object v:((Map<?,?>)o).values())pure(v);return;}
    if(o instanceof List){for(Object v:(List<?>)o)pure(v);return;}
    throw new AssertionError("native handle escaped: "+o.getClass());
  }
  public static void main(String[]args)throws Exception {
    for(int n=0;n<500;n++) {
      Map<?,?> s=(Map<?,?>)snapshot(new Result(true,new Data(64)));pure(s);
      if(!"chosen".equals(s.get("chatId")))throw new AssertionError("chat identity");
      List<?> messages=(List<?>)s.get("messages");
      if(messages.size()!=64)throw new AssertionError("history truncated");
      Map<?,?> first=(Map<?,?>)messages.get(0),last=(Map<?,?>)messages.get(63);
      if(!Long.valueOf(100).equals(first.get("timestamp"))||!"".equals(first.get("roleName"))||!"Sol".equals(last.get("roleName")))throw new AssertionError("metadata changed");
      if(!"selected variant \"你好\"\n🙂".equals(first.get("content")))throw new AssertionError("text changed");
    }
    try{snapshot(new Result(false,new Data(1)));throw new AssertionError("failure accepted");}catch(IllegalStateException expected){}
    try{snapshot(new Result(true,new Data(65)));throw new AssertionError("unbounded history accepted");}catch(IllegalStateException expected){}
    Method emit=NativeHistoryRead.class.getMethod("emitEvent",Object.class,Object.class,String.class);
    android.content.Context ctx=new android.content.Context(); android.net.Uri uri=new android.net.Uri("request");
    for(int i=0;i<10000;i++)if(!Boolean.TRUE.equals(emit.invoke(null,ctx,uri,"chunk-"+i)))throw new AssertionError("lost ack");
    if(ctx.resolver.calls!=10000||!"chunk-9999".equals(ctx.resolver.last))throw new AssertionError("event mutation");
    ctx.resolver.status="rejected";if(!Boolean.FALSE.equals(emit.invoke(null,ctx,uri,"cancelled")))throw new AssertionError("revoked request accepted");
    ctx.resolver.status=null;if(!Boolean.FALSE.equals(emit.invoke(null,ctx,uri,"closed")))throw new AssertionError("null insert accepted");
    System.out.println("PASS: 500 snapshots preserve 64 official messages as pure values; errors/limits; 10000 event acknowledgements; rejection/null");
  }
}
