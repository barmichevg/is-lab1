import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.websocket.*;
import ru.itmo.is.security.AuthSessions;
import ru.itmo.is.websocket.OrganizationWebSocket;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Deterministic audit reproduction against unchanged production classes; no server needed. */
public class WebSocketLogoutRepro {
    public static void main(String[] args) throws Exception {
        String token=UUID.randomUUID().toString();
        AtomicBoolean authenticated=new AtomicBoolean(true),open=new AtomicBoolean(true),firstProperties=new AtomicBoolean(true);
        CountDownLatch checkedBeforeAdd=new CountDownLatch(1),allowAdd=new CountDownLatch(1);
        List<String> sent=new CopyOnWriteArrayList<>();
        Map<String,Object> properties=new ConcurrentHashMap<>();
        HttpSession http=(HttpSession)Proxy.newProxyInstance(HttpSession.class.getClassLoader(),new Class<?>[]{HttpSession.class},(p,m,a)->{
            if(m.getName().equals("getAttribute"))return authenticated.get()?"audit-user":null;
            throw new AssertionError("Unexpected HTTP method: "+m.getName());
        });
        RemoteEndpoint.Async remote=(RemoteEndpoint.Async)Proxy.newProxyInstance(RemoteEndpoint.Async.class.getClassLoader(),new Class<?>[]{RemoteEndpoint.Async.class},(p,m,a)->{
            if(m.getName().equals("sendText")){sent.add((String)a[0]);((SendHandler)a[1]).onResult(new SendResult());return null;}
            throw new AssertionError("Unexpected remote method: "+m.getName());
        });
        Session ws=(Session)Proxy.newProxyInstance(Session.class.getClassLoader(),new Class<?>[]{Session.class},(p,m,a)->{
            return switch(m.getName()){
                case "hashCode" -> System.identityHashCode(p);
                case "equals" -> p==a[0];
                case "getRequestParameterMap" -> Map.of("token",List.of(token));
                case "getUserProperties" -> {
                    if(firstProperties.compareAndSet(true,false)){
                        checkedBeforeAdd.countDown();
                        if(!allowAdd.await(5,TimeUnit.SECONDS))throw new AssertionError("Audit latch timeout");
                    }
                    yield properties;
                }
                case "isOpen" -> open.get();
                case "getAsyncRemote" -> remote;
                case "close" -> {open.set(false);yield null;}
                default -> throw new AssertionError("Unexpected WS method: "+m.getName());
            };
        });
        var endpoint=new OrganizationWebSocket();
        var executor=Executors.newSingleThreadExecutor();
        AuthSessions.register(http,token);
        try {
            Future<?> opening=executor.submit(()->endpoint.onOpen(ws));
            if(!checkedBeforeAdd.await(5,TimeUnit.SECONDS))throw new AssertionError("Did not reach valid-before-add window");
            authenticated.set(false);
            new AuthSessions().sessionDestroyed(new HttpSessionEvent(http));
            if(AuthSessions.valid(token))throw new AssertionError("Token should already be revoked");
            allowAdd.countDown();opening.get(5,TimeUnit.SECONDS);
            OrganizationWebSocket.broadcast("CREATED",42);
            if(!open.get()||!sent.equals(List.of("{\"type\":\"CREATED\",\"id\":42}")))throw new AssertionError("Expected surviving socket/event; got "+sent);
            System.out.println("CONFIRMED: token revoked during onOpen, but socket is subsequently added and still receives CREATED/id42.");
            System.out.println("Impact: post-logout event metadata leak only (type/id); this test does not demonstrate access to protected REST data.");
        } finally {
            allowAdd.countDown();endpoint.onClose(ws);new AuthSessions().sessionDestroyed(new HttpSessionEvent(http));
            executor.shutdownNow();executor.awaitTermination(5,TimeUnit.SECONDS);
        }
    }
}
