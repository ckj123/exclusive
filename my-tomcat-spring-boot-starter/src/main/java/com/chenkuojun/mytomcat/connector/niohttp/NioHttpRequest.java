package com.chenkuojun.mytomcat.connector.niohttp;

/** this class copies methods from org.apache.catalina.connector.HttpRequestBase
 *  and org.apache.catalina.connector.http.HttpRequestImpl.
 *  The HttpRequestImpl class employs a pool of HttpHeader objects for performance
 *  These two classes will be explained in Chapter 4.
 */

import com.chenkuojun.mytomcat.connector.http.ApplicationContextFacade;
import com.chenkuojun.mytomcat.utils.Enumerator;
import com.chenkuojun.mytomcat.utils.ParameterMap;
import com.chenkuojun.mytomcat.utils.RequestUtil;
import lombok.Cleanup;
import lombok.extern.slf4j.Slf4j;
import javax.servlet.*;
import javax.servlet.http.*;
import java.io.*;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;

@Slf4j
public class NioHttpRequest implements HttpServletRequest {
  private String url;
  private String uri;
  private HashMap<String,String> param = new HashMap<>();

  private String contentType;
  private int contentLength;
  private InetAddress inetAddress;
  private InputStream input;
  private String method;
  private String protocol;
  private String queryString;
  private String requestURI;
  private String serverName;
  private int serverPort;
  private Socket socket;
  private boolean requestedSessionCookie;
  private String requestedSessionId;
  private boolean requestedSessionURL;

  private String servletPath = "";

  /**
   * The request attributes for this request.
   */
  protected HashMap attributes = new HashMap();
  /**
   * The authorization credentials sent with this Request.
   */
  protected String authorization = null;
  /**
   * The context path for this request.
   */
  protected String contextPath = "";
  /**
   * The set of cookies associated with this Request.
   */
  protected ArrayList cookies = new ArrayList();
  /**
   * An empty collection to use for returning empty Enumerations.  Do not
   * add any elements to this collection!
   */
  protected static ArrayList empty = new ArrayList();

  /**
   * The set of SimpleDateFormat formats to use in getDateHeader().
   */
  protected SimpleDateFormat formats[] = {
    new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US),
    new SimpleDateFormat("EEEEEE, dd-MMM-yy HH:mm:ss zzz", Locale.US),
    new SimpleDateFormat("EEE MMMM d HH:mm:ss yyyy", Locale.US)
  };

  /**
   * The HTTP headers associated with this Request, keyed by name.  The
   * values are ArrayLists of the corresponding header values.
   */
  protected HashMap headers = new HashMap();
  /**
   * The parsed parameters for this request.  This is populated only if
   * parameter information is requested via one of the
   * <code>getParameter()</code> family of method calls.  The key is the
   * parameter name, while the value is a String array of values for this
   * parameter.
   * <p>
   * <strong>IMPLEMENTATION NOTE</strong> - Once the parameters for a
   * particular request are parsed and stored here, they are not modified.
   * Therefore, application level access to the parameters need not be
   * synchronized.
   */
  protected ParameterMap parameters = null;

  /**
   * Have the parameters for this request been parsed yet?
   */
  protected boolean parsed = false;
  protected String pathInfo = null;

  /**
   * The reader that has been returned by <code>getReader</code>, if any.
   */
  protected BufferedReader reader = null;

  /**
   * The ServletInputStream that has been returned by
   * <code>getInputStream()</code>, if any.
   */
  protected ServletInputStream stream = null;

  public NioHttpRequest(SelectionKey selectionKey) throws IOException, ServletException {
    //  从selectionKey获取通道
    SocketChannel channel = (SocketChannel) selectionKey.channel();
    String httpRequest = "";
    @Cleanup("flip") ByteBuffer bb = ByteBuffer.allocate(16*1024);   //  从堆内存中获取内存
    int length = 0; //  读取byte数组的长度
    length = channel.read(bb);  //  从通道中读取数据到ByteBuffer容器中
    if (length < 0){
      selectionKey.cancel();  //  取消该契约
    }else {
      httpRequest = new String(bb.array()).trim();    //  将ByteBuffer转为String
      //log.info("请求参数 -> {}",httpRequest);
      parseRequest(httpRequest);
      log.info("当前请求详情 -> {}",this);

      // 解析请求

    }
  }

  private void parseRequest(String httpRequest) throws ServletException {
    String httpHead = httpRequest.split("\n")[0];   //  获取请求头
    url = httpHead.split("\\s")[1].split("\\?")[0]; //  获取请求路径
    this.setUrl(url);
    String path = httpHead.split("\\s")[1]; //  请求全路径，包含get的参数数据
    method = httpHead.split("\\s")[0];
    int index = httpRequest.indexOf("HTTP");
    // 获取uri
    uri = httpRequest.substring(3 + 1, index - 1);// 用index-1可以去掉连接中的空格
    log.info("请求的url为:{}",url);
    // 拆分get请求的参数数据
    String[] params = path.indexOf("?") > 0 ? path.split("\\?")[1].split("\\&") : null;
    if (params != null) {
      try {
        for (String tmp : params) {
          param.put(tmp.split("\\=")[0], tmp.split("\\=")[1]);
        }
      } catch (NullPointerException e) {
        e.printStackTrace();
      }
    }
    String protocol = "http";

    // Validate the incoming request line
    if (method.length() < 1) {
      throw new ServletException("Missing HTTP request method");
    }


    // Checking for an absolute URI (with the HTTP protocol)
    if (!uri.startsWith("/")) {
      int pos = uri.indexOf("://");
      // Parsing out protocol and host name
      if (pos != -1) {
        pos = uri.indexOf('/', pos + 3);
        if (pos == -1) {
          uri = "";
        } else {
          uri = uri.substring(pos);
        }
      }
    }

    // Parse any requested session ID out of the request URI
    String match = ";jsessionid=";
    int semicolon = uri.indexOf(match);
    if (semicolon >= 0) {
      String rest = uri.substring(semicolon + match.length());
      int semicolon2 = rest.indexOf(';');
      if (semicolon2 >= 0) {
        this.setRequestedSessionId(rest.substring(0, semicolon2));
        rest = rest.substring(semicolon2);
      } else {
        this.setRequestedSessionId(rest);
        rest = "";
      }
      this.setRequestedSessionURL(true);
      uri = uri.substring(0, semicolon) + rest;
    } else {
      this.setRequestedSessionId(null);
      this.setRequestedSessionURL(false);
    }

    // Normalize URI (using String operations at the moment)
    String normalizedUri = normalize(uri);

    // Set the corresponding request properties
    this.setMethod(method);
    this.setProtocol(protocol);
    if (normalizedUri != null) {
      this.setRequestURI(normalizedUri);
    } else {
      this.setRequestURI(uri);
    }

    if (normalizedUri == null) {
      throw new ServletException("Invalid URI: " + uri + "'");
    }
  }

  public String getUrl(String url) {
    return this.url;
  }
  public void setUrl(String url) {
    this.url = url;
  }


  protected String normalize(String path) {
    if (path == null)
      return null;
    // Create a place for the normalized path
    String normalized = path;

    // Normalize "/%7E" and "/%7e" at the beginning to "/~"
    if (normalized.startsWith("/%7E") || normalized.startsWith("/%7e"))
      normalized = "/~" + normalized.substring(4);

    // Prevent encoding '%', '/', '.' and '\', which are special reserved
    // characters
    if ((normalized.indexOf("%25") >= 0)
            || (normalized.indexOf("%2F") >= 0)
            || (normalized.indexOf("%2E") >= 0)
            || (normalized.indexOf("%5C") >= 0)
            || (normalized.indexOf("%2f") >= 0)
            || (normalized.indexOf("%2e") >= 0)
            || (normalized.indexOf("%5c") >= 0)) {
      return null;
    }

    if (normalized.equals("/."))
      return "/";

    // Normalize the slashes and add leading slash if necessary
    if (normalized.indexOf('\\') >= 0)
      normalized = normalized.replace('\\', '/');
    if (!normalized.startsWith("/"))
      normalized = "/" + normalized;

    // Resolve occurrences of "//" in the normalized path
    while (true) {
      int index = normalized.indexOf("//");
      if (index < 0)
        break;
      normalized = normalized.substring(0, index) +
              normalized.substring(index + 1);
    }

    // Resolve occurrences of "/./" in the normalized path
    while (true) {
      int index = normalized.indexOf("/./");
      if (index < 0)
        break;
      normalized = normalized.substring(0, index) +
              normalized.substring(index + 2);
    }

    // Resolve occurrences of "/../" in the normalized path
    while (true) {
      int index = normalized.indexOf("/../");
      if (index < 0)
        break;
      if (index == 0)
        return (null);  // Trying to go outside our context
      int index2 = normalized.lastIndexOf('/', index - 1);
      normalized = normalized.substring(0, index2) +
              normalized.substring(index + 3);
    }

    // Declare occurrences of "/..." (three or more dots) to be invalid
    // (on some Windows platforms this walks the directory tree!!!)
    if (normalized.indexOf("/...") >= 0)
      return (null);

    // Return the normalized path that we have completed
    return (normalized);

  }

  public void addHeader(String name, String value) {
    name = name.toLowerCase();
    synchronized (headers) {
      ArrayList values = (ArrayList) headers.get(name);
      if (values == null) {
        values = new ArrayList();
        headers.put(name, values);
      }
      values.add(value);
    }
  }

  /**
   * Parse the parameters of this request, if it has not already occurred.
   * If parameters are present in both the query string and the request
   * content, they are merged.
   */
  protected void parseParameters() {
    if (parsed)
      return;
    ParameterMap results = parameters;
    if (results == null)
      results = new ParameterMap();
    results.setLocked(false);
    String encoding = getCharacterEncoding();
    if (encoding == null)
      encoding = "ISO-8859-1";

    // Parse any parameters specified in the query string
    String queryString = getQueryString();
    try {
      RequestUtil.parseParameters(results, queryString, encoding);
    }
    catch (UnsupportedEncodingException e) {
      ;
    }

    // Parse any parameters specified in the input stream
    String contentType = getContentType();
    if (contentType == null)
      contentType = "";
    int semicolon = contentType.indexOf(';');
    if (semicolon >= 0) {
      contentType = contentType.substring(0, semicolon).trim();
    }
    else {
      contentType = contentType.trim();
    }
    if ("POST".equals(getMethod()) && (getContentLength() > 0)
      && "application/x-www-form-urlencoded".equals(contentType)) {
      try {
        int max = getContentLength();
        int len = 0;
        byte buf[] = new byte[getContentLength()];
        ServletInputStream is = getInputStream();
        while (len < max) {
          int next = is.read(buf, len, max - len);
          if (next < 0 ) {
            break;
          }
          len += next;
        }
        is.close();
        if (len < max) {
          throw new RuntimeException("Content length mismatch");
        }
        RequestUtil.parseParameters(results, buf, encoding);
      }
      catch (UnsupportedEncodingException ue) {
        ;
      }
      catch (IOException e) {
        throw new RuntimeException("Content read fail");
      }
    }

    // Store the final results
    results.setLocked(true);
    parsed = true;
    parameters = results;
  }

  public void addCookie(Cookie cookie) {
    synchronized (cookies) {
      cookies.add(cookie);
    }
  }

  /**
   * Create and return a ServletInputStream to read the content
   * associated with this Request.  The default implementation creates an
   * instance of RequestStream associated with this request, but this can
   * be overridden if necessary.
   *
   * @exception IOException if an input/output error occurs
   * @return  ServletInputStream
   */
  public ServletInputStream createInputStream() throws IOException {
    return (new NioRequestStream(this));
  }

  public InputStream getStream() {
    return input;
  }
  public void setContentLength(int length) {
    this.contentLength = length;
  }

  public void setContentType(String type) {
    this.contentType = type;
  }

  public void setInet(InetAddress inetAddress) {
    this.inetAddress = inetAddress;
  }

  public void setContextPath(String path) {
    if (path == null)
      this.contextPath = "";
    else
      this.contextPath = path;
  }

  public void setMethod(String method) {
    this.method = method;
  }

  public void setPathInfo(String path) {
    this.pathInfo = path;
  }

  public void setProtocol(String protocol) {
    this.protocol = protocol;
  }

  public void setQueryString(String queryString) {
    this.queryString = queryString;
  }

  public void setRequestURI(String requestURI) {
    this.requestURI = requestURI;
  }
  /**
   * Set the name of the server (virtual host) to process this request.
   *
   * @param name The server name
   */
  public void setServerName(String name) {
    this.serverName = name;
  }
  /**
   * Set the port number of the server to process this request.
   *
   * @param port The server port
   */
  public void setServerPort(int port) {
    this.serverPort = port;
  }

  public void setSocket(Socket socket) {
    this.socket = socket;
  }

  /**
   * Set a flag indicating whether or not the requested session ID for this
   * request came in through a cookie.  This is normally called by the
   * HTTP Connector, when it parses the request headers.
   *
   * @param flag The new flag
   */
  public void setRequestedSessionCookie(boolean flag) {
    this.requestedSessionCookie = flag;
  }

  public void setRequestedSessionId(String requestedSessionId) {
    this.requestedSessionId = requestedSessionId;
  }

  public void setRequestedSessionURL(boolean flag) {
    requestedSessionURL = flag;
  }

  /* implementation of the HttpServletRequest*/
  public Object getAttribute(String name) {
    synchronized (attributes) {
      return (attributes.get(name));
    }
  }

  public Enumeration getAttributeNames() {
    synchronized (attributes) {
      return (new Enumerator(attributes.keySet()));
    }
  }

  public String getAuthType() {
    return null;
  }

  public String getCharacterEncoding() {
    return null;
  }

  public int getContentLength() {
    return contentLength ;
  }

  @Override
  public long getContentLengthLong() {
    return 0;
  }

  public String getContentType() {
    return contentType;
  }

  public String getContextPath() {
    return contextPath;
  }

  public Cookie[] getCookies() {
    synchronized (cookies) {
      if (cookies.size() < 1)
        return (null);
      Cookie results[] = new Cookie[cookies.size()];
      return ((Cookie[]) cookies.toArray(results));
    }
  }

  public long getDateHeader(String name) {
    String value = getHeader(name);
    if (value == null)
      return (-1L);

    // Work around a bug in SimpleDateFormat in pre-JDK1.2b4
    // (Bug Parade bug #4106807)
    value += " ";

    // Attempt to convert the date header in a variety of formats
    for (int i = 0; i < formats.length; i++) {
      try {
        Date date = formats[i].parse(value);
        return (date.getTime());
      }
      catch (ParseException e) {
        ;
      }
    }
    throw new IllegalArgumentException(value);
  }

  public String getHeader(String name) {
    name = name.toLowerCase();
    synchronized (headers) {
      ArrayList values = (ArrayList) headers.get(name);
      if (values != null)
        return ((String) values.get(0));
      else
        return null;
    }
  }

  public Enumeration getHeaderNames() {
    synchronized (headers) {
      return (new Enumerator(headers.keySet()));
    }
  }

  public Enumeration getHeaders(String name) {
    name = name.toLowerCase();
    synchronized (headers) {
      ArrayList values = (ArrayList) headers.get(name);
      if (values != null)
        return (new Enumerator(values));
      else
        return (new Enumerator(empty));
    }
  }

  public ServletInputStream getInputStream() throws IOException {
    if (reader != null)
      throw new IllegalStateException("getInputStream has been called");

    if (stream == null)
      stream = createInputStream();
    return (stream);
  }

  public int getIntHeader(String name) {
    String value = getHeader(name);
    if (value == null)
      return (-1);
    else
      return (Integer.parseInt(value));
  }

  public Locale getLocale() {
    return Locale.SIMPLIFIED_CHINESE;
  }

  public Enumeration getLocales() {
    return null;
  }

  public String getMethod() {
    return method;
  }

  public String getParameter(String name) {
    parseParameters();
    String values[] = (String[]) parameters.get(name);
    if (values != null)
      return (values[0]);
    else
      return (null);
  }

  public Map getParameterMap() {
    parseParameters();
    return (this.parameters);
  }

  public Enumeration getParameterNames() {
    parseParameters();
    return (new Enumerator(parameters.keySet()));
  }

  public String[] getParameterValues(String name) {
    parseParameters();
    String values[] = (String[]) parameters.get(name);
    if (values != null)
      return (values);
    else
      return null;
  }

  public String getPathInfo() {
    return pathInfo;
  }

  public String getPathTranslated() {
    return null;
  }

  public String getProtocol() {
    return protocol;
  }

  public String getQueryString() {
    return queryString;
  }

  public BufferedReader getReader() throws IOException {
    if (stream != null)
      throw new IllegalStateException("getInputStream has been called.");
    if (reader == null) {
      String encoding = getCharacterEncoding();
      if (encoding == null)
        encoding = "ISO-8859-1";
      InputStreamReader isr =
        new InputStreamReader(createInputStream(), encoding);
        reader = new BufferedReader(isr);
    }
    return (reader);
  }

  public String getRealPath(String path) {
    return null;
  }

  @Override
  public int getRemotePort() {
    return 0;
  }

  @Override
  public String getLocalName() {
    return null;
  }

  @Override
  public String getLocalAddr() {
    return null;
  }

  @Override
  public int getLocalPort() {
    return 0;
  }

  @Override
  public ServletContext getServletContext() {
    return new ApplicationContextFacade();
  }

  @Override
  public AsyncContext startAsync() throws IllegalStateException {
    return null;
  }

  @Override
  public AsyncContext startAsync(ServletRequest servletRequest, ServletResponse servletResponse) throws IllegalStateException {
    return null;
  }

  @Override
  public boolean isAsyncStarted() {
    return false;
  }

  @Override
  public boolean isAsyncSupported() {
    return false;
  }

  @Override
  public AsyncContext getAsyncContext() {
    return null;
  }

  @Override
  public DispatcherType getDispatcherType() {
    return null;
  }

  public String getRemoteAddr() {
    return null;
  }

  public String getRemoteHost() {
    return "127.0.0.1";
  }

  public String getRemoteUser() {
    return null;
  }

  public RequestDispatcher getRequestDispatcher(String path) {
    ServletContext context = getServletContext();
    if (context == null) {
      return null;
    }

    if (path == null) {
      return null;
    }

    int fragmentPos = path.indexOf('#');
    if (fragmentPos > -1) {
      log.warn("request.fragmentInDispatchPath", path);
      path = path.substring(0, fragmentPos);
    }

    // If the path is already context-relative, just pass it through
    if (path.startsWith("/")) {
      return context.getRequestDispatcher(path);
    }
    // Convert a request-relative path to a context-relative one
    String servletPath = (String) getAttribute(
            RequestDispatcher.INCLUDE_SERVLET_PATH);
    if (servletPath == null) {
      servletPath = getServletPath();
    }

    // Add the path info, if there is any
    String pathInfo = getPathInfo();
    String requestPath = null;

    if (pathInfo == null) {
      requestPath = servletPath;
    } else {
      requestPath = servletPath + pathInfo;
    }

    int pos = requestPath.lastIndexOf('/');
    String relative;
    if (true) {
      if (pos >= 0) {
        relative = URLEncoder.encode(requestPath.substring(0, pos + 1),StandardCharsets.UTF_8) + path;
      } else {
        relative = URLEncoder.encode(requestPath, StandardCharsets.UTF_8) + path;
      }
    } else {
      if (pos >= 0) {
        relative = requestPath.substring(0, pos + 1) + path;
      } else {
        relative = requestPath + path;
      }
    }

    return context.getRequestDispatcher(relative);
  }

  public String getScheme() {
   return null;
  }

  public String getServerName() {
    return null;
  }

  public int getServerPort() {
    return 0;
  }

  public String getRequestedSessionId() {
    return null;
  }

  public String getRequestURI() {
    return requestURI;
  }

  public StringBuffer getRequestURL() {
    return null;
  }

  public HttpSession getSession() {
    return null;
  }

  @Override
  public String changeSessionId() {
    return null;
  }

  public HttpSession getSession(boolean create) {
    return null;
  }

  public void setServletPath(String servletPath) {
    this.servletPath = servletPath;
  }

  public String getServletPath() {
    return this.servletPath;
  }

  public Principal getUserPrincipal() {
    return null;
  }

  public boolean isRequestedSessionIdFromCookie() {
    return false;
  }

  public boolean isRequestedSessionIdFromUrl() {
    return isRequestedSessionIdFromURL();
  }

  @Override
  public boolean authenticate(HttpServletResponse httpServletResponse) throws IOException, ServletException {
    return false;
  }

  @Override
  public void login(String s, String s1) throws ServletException {

  }

  @Override
  public void logout() throws ServletException {

  }

  @Override
  public Collection<Part> getParts() throws IOException, ServletException {
    return null;
  }

  @Override
  public Part getPart(String s) throws IOException, ServletException {
    return null;
  }

  @Override
  public <T extends HttpUpgradeHandler> T upgrade(Class<T> aClass) throws IOException, ServletException {
    return null;
  }

  public boolean isRequestedSessionIdFromURL() {
    return false;
  }

  public boolean isRequestedSessionIdValid() {
    return false;
  }

  public boolean isSecure() {
    return false;
  }

  public boolean isUserInRole(String role) {
    return false;
  }

  public void removeAttribute(String attribute) {
  }

  public void setAttribute(String key, Object value) {
      this.attributes.put(key,value);
  }

  /**
   * Set the authorization credentials sent with this request.
   *
   * @param authorization The new authorization credentials
   */
  public void setAuthorization(String authorization) {
    this.authorization = authorization;
  }

  public void setCharacterEncoding(String encoding) throws UnsupportedEncodingException {
  }
}
