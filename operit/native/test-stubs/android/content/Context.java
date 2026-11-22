package android.content;
public class Context {
  public final ContentResolver resolver=new ContentResolver();
  public ContentResolver getContentResolver(){return resolver;}
}
