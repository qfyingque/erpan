package android.content;
public class ContentValues {
  public String value;
  public void put(String key,String value){if(!"event".equals(key))throw new AssertionError(key);this.value=value;}
}
