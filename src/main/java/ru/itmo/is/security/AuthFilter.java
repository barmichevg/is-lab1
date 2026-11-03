package ru.itmo.is.security;

import jakarta.servlet.*;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.net.URI;

@WebFilter(urlPatterns={"/api/*","/ws/*"})
public class AuthFilter implements Filter {
    public static boolean sameOrigin(HttpServletRequest request) {
        String origin=request.getHeader("Origin");
        if(origin==null) return true; // CLI clients authenticate using the session and CSRF token.
        try {
            URI uri=URI.create(origin);
            int port=uri.getPort()<0 ? ("https".equals(uri.getScheme())?443:80) : uri.getPort();
            return request.getScheme().equals(uri.getScheme()) && request.getServerName().equalsIgnoreCase(uri.getHost()) && request.getServerPort()==port;
        } catch(RuntimeException e) { return false; }
    }
    @Override public void doFilter(ServletRequest req,ServletResponse res,FilterChain chain) throws IOException,ServletException {
        HttpServletRequest r=(HttpServletRequest)req; HttpServletResponse s=(HttpServletResponse)res;
        s.setHeader("Cache-Control","no-store");
        s.setHeader("X-Content-Type-Options","nosniff");
        if(!sameOrigin(r)) { fail(s,403,"Origin rejected"); return; }
        if(r.getRequestURI().equals(r.getContextPath()+"/api/auth/login") || r.getRequestURI().equals(r.getContextPath()+"/api/auth/register")) { chain.doFilter(req,res); return; }
        HttpSession session=r.getSession(false);
        if(session==null || session.getAttribute("user")==null) { fail(s,401,"Authentication required"); return; }
        if(!r.getMethod().equals("GET") && !r.getMethod().equals("HEAD")) {
            if(!session.getAttribute("csrf").equals(r.getHeader("X-CSRF-Token"))) { fail(s,403,"CSRF token required"); return; }
        }
        chain.doFilter(req,res);
    }
    public static void fail(HttpServletResponse response,int status,String error) throws IOException {
        response.setStatus(status); response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\""+error+"\"}");
    }
}
