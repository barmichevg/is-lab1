package ru.itmo.is;
import org.junit.jupiter.api.Test;
import jakarta.json.Json;
import ru.itmo.is.dto.RequestParser;
import java.net.*;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
class RegistrationIT {
 static HttpClient client(){return HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();}
 static String base(){return DatabaseIT.env("TEST_BASE_URL");}
 static HttpResponse<String> auth(HttpClient c,String route,String user,String password)throws Exception {
  String body=Json.createObjectBuilder().add("username",user).add("password",password).build().toString();
  return c.send(HttpRequest.newBuilder(URI.create(base()+"/api/auth/"+route)).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
 }
 @Test void registerLoginAndSessionWithUnrestrictedPasswords()throws Exception {
  for(String password:List.of("", "1", "a".repeat(300))){
   String user="registration_"+UUID.randomUUID();var c=client();
   var registered=auth(c,"register",user,password);assertEquals(201,registered.statusCode(),registered.body());
   assertEquals(user,RequestParser.object(registered.body()).getString("username"));
   assertFalse(registered.body().contains("password"));
   assertEquals(200,c.send(HttpRequest.newBuilder(URI.create(base()+"/api/organizations")).build(),HttpResponse.BodyHandlers.ofString()).statusCode());
   assertEquals(200,auth(client(),"login",user,password).statusCode());
   assertEquals(401,auth(client(),"login",user,password+"wrong").statusCode());
   assertEquals(409,auth(client(),"register",user,"other").statusCode());
  }
 }
 @Test void duplicateConcurrentRegistrationAndOrigin()throws Exception {
  String user="parallel_"+UUID.randomUUID();var pool=Executors.newFixedThreadPool(2);
  try {
   var a=pool.submit(()->auth(client(),"register",user,"a").statusCode());
   var b=pool.submit(()->auth(client(),"register",user,"b").statusCode());
   assertEquals(Set.of(201,409),Set.of(a.get(),b.get()));
  }finally{pool.shutdownNow();}
  var response=client().send(HttpRequest.newBuilder(URI.create(base()+"/api/auth/register")).header("Origin","http://other.invalid").POST(HttpRequest.BodyPublishers.ofString("{}")).build(),HttpResponse.BodyHandlers.ofString());
  assertEquals(403,response.statusCode());
 }
}
