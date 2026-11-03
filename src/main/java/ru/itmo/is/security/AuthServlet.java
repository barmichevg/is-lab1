package ru.itmo.is.security;

import jakarta.servlet.*;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.*;
import jakarta.json.Json;
import ru.itmo.is.dto.RequestParser;
import java.io.*;
import java.nio.file.*;
import java.util.*;

@WebServlet(urlPatterns="/api/auth/*",loadOnStartup=1)
public class AuthServlet extends HttpServlet {
    private UserStore users;
    private String dummyHash;
    private final LoginRateLimiter rateLimiter;
    public AuthServlet() { this(new LoginRateLimiter()); }
    AuthServlet(LoginRateLimiter rateLimiter) { this.rateLimiter=Objects.requireNonNull(rateLimiter); }
    @Override public void init() throws ServletException {
        String path=System.getenv("AUTH_USERS_FILE");
        if(path==null || path.isBlank()) throw new ServletException("AUTH_USERS_FILE must point to a password hash file");
        try { users = new UserStore(Path.of(path)); }
        catch(IOException e) { throw new ServletException("Cannot load authentication configuration",e); }
        dummyHash = Passwords.hash(UUID.randomUUID().toString());
    }
    @Override protected void doGet(HttpServletRequest r,HttpServletResponse s) throws IOException {
        if(!"/session".equals(r.getPathInfo())) { AuthFilter.fail(s,404,"Not found"); return; }
        sessionResponse(r.getSession(),s);
    }
    @Override protected void doPost(HttpServletRequest r,HttpServletResponse s) throws IOException {
        if("/logout".equals(r.getPathInfo())) { r.getSession().invalidate(); s.setStatus(204); return; }
        boolean register = "/register".equals(r.getPathInfo());
        if(!register && !"/login".equals(r.getPathInfo())) { AuthFilter.fail(s,404,"Not found"); return; }

        var peer=rateLimiter.allowPeer(r.getRemoteAddr());
        if(!peer.allowed()) {
            s.setHeader("Retry-After",Long.toString(peer.retryAfterSeconds()));
            AuthFilter.fail(s,429,"Too many authentication attempts; retry later"); return;
        }
        if(r.getContentType()==null || !r.getContentType().toLowerCase(Locale.ROOT).startsWith("application/json")) { AuthFilter.fail(s,415,"JSON required"); return; }
        try {
            byte[] bytes=r.getInputStream().readNBytes(65537);
            if(bytes.length>65536) { AuthFilter.fail(s,413,"Request too large"); return; }
            String body=new String(bytes,java.nio.charset.StandardCharsets.UTF_8);
            var j=RequestParser.object(body);
            String user=RequestParser.string(j,"username","username");
            if (!LoginRateLimiter.validUsername(user)) { AuthFilter.fail(s,400,"Invalid username"); return; }
            if (!(j.get("password") instanceof jakarta.json.JsonString value)) { AuthFilter.fail(s,400,"Password must be a string"); return; }

            var decision=rateLimiter.allowUsername(register ? LoginRateLimiter.Operation.REGISTER : LoginRateLimiter.Operation.LOGIN,
                    user);
            if(!decision.allowed()) {
                s.setHeader("Retry-After",Long.toString(decision.retryAfterSeconds()));
                AuthFilter.fail(s,429,"Too many authentication attempts; retry later"); return;
            }
            String password=value.getString();
            if (register) {
                try {
                    if (!users.register(user, Passwords.hash(password))) { AuthFilter.fail(s,409,"Username already exists"); return; }
                } catch (IOException e) {
                    getServletContext().log("Cannot save registered account", e);
                    AuthFilter.fail(s,503,"Cannot save account"); return;
                }
                s.setStatus(201);
            } else {
                String encoded=users.hash(user);
                boolean valid=Passwords.verify(password,encoded==null ? dummyHash : encoded);
                if(!valid || encoded==null) { AuthFilter.fail(s,401,"Invalid username or password"); return; }
            }
            HttpSession old=r.getSession(false); if(old!=null) old.invalidate();
            HttpSession session=r.getSession(true); session.setMaxInactiveInterval(1800);
            session.setAttribute("user",user); session.setAttribute("csrf",UUID.randomUUID().toString());
            String token=UUID.randomUUID().toString(); session.setAttribute("wsToken",token); AuthSessions.register(session,token);
            sessionResponse(session,s);
        } catch(ru.itmo.is.exception.ApiValidationException e) { AuthFilter.fail(s,400,"Invalid login JSON"); }
    }
    private void sessionResponse(HttpSession session,HttpServletResponse response) throws IOException {
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(Json.createObjectBuilder().add("username",(String)session.getAttribute("user"))
            .add("csrf",(String)session.getAttribute("csrf")).add("wsToken",(String)session.getAttribute("wsToken")).build().toString());
    }
}
