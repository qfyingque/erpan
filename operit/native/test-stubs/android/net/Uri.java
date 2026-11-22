package android.net;
public class Uri {
  final String status;
  public Uri(String status){this.status=status;}
  public String getQueryParameter(String key){if(!"status".equals(key))throw new AssertionError(key);return status;}
}
