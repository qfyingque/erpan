package android.content;
import android.net.Uri;
public class ContentResolver {
  public int calls;public String last;public String status="accepted";
  public Uri insert(Uri uri,ContentValues values){calls++;last=values.value;return status==null?null:new Uri(status);}
}
