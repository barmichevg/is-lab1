package ru.itmo.is;

import org.junit.jupiter.api.*;
import jakarta.json.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Creates its own fixtures; a completely empty DB is a valid starting point. */
class ApiIT {
    String base,csrf,wsToken; HttpClient client;
    Set<Integer> organizations=new LinkedHashSet<>(),coordinates=new LinkedHashSet<>(),addresses=new LinkedHashSet<>(),locations=new LinkedHashSet<>();
    @BeforeEach void setup() throws Exception {
        base=DatabaseIT.env("TEST_BASE_URL");
        client=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).connectTimeout(Duration.ofSeconds(10)).build();
        var login=Json.createObjectBuilder().add("username",DatabaseIT.env("TEST_AUTH_USER")).add("password",DatabaseIT.env("TEST_AUTH_PASSWORD")).build();
        var response=send("POST","/api/auth/login",login.toString(),null);
        assertEquals(200,response.statusCode(),response.body());
        var session=parse(response.body());csrf=session.getString("csrf");wsToken=session.getString("wsToken");
    }
    HttpResponse<String> send(String method,String path,String body,String key) throws Exception {
        var b=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(20)).header("Content-Type","application/json");
        if(csrf!=null)b.header("X-CSRF-Token",csrf);if(key!=null)b.header("X-Idempotency-Key",key);
        return client.send(b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    static JsonObject parse(String body){return ru.itmo.is.dto.RequestParser.object(body);}
    JsonObject ok(String method,String path,String body,String key) throws Exception {
        var r=send(method,path,body,key);assertTrue(r.statusCode()>=200&&r.statusCode()<300,r.statusCode()+" "+r.body());return parse(r.body());
    }
    String fixture(String name,String employees){return Json.createObjectBuilder().add("name",name).add("annualTurnover",10).add("employeesCount",employees).add("rating",1).add("type","COMMERCIAL")
        .add("coordinates",Json.createObjectBuilder().add("x",1).add("y",2))
        .add("officialAddress",Json.createObjectBuilder().add("zipCode","test").add("town",Json.createObjectBuilder().add("name","test").add("x",1).add("y",2).add("z",3))).build().toString();}
    JsonObject create(String name,String employees) throws Exception {var o=ok("POST","/api/organizations",fixture(name,employees),UUID.randomUUID().toString());assertEquals("1",o.getString("version"));track(o);return o;}
    // Deliberately require the DTO's original version; never fetch/fill a missing one.
    JsonObjectBuilder updateBody(JsonObject original,String name) {
        var body=Json.createObjectBuilder().add("version",original.getString("version")).add("name",name)
            .add("annualTurnover",original.getJsonNumber("annualTurnover")).add("employeesCount",original.getString("employeesCount"))
            .add("rating",original.getInt("rating")).add("type",original.getString("type"))
            .add("coordinates",Json.createObjectBuilder().add("id",referenceId(original,"coordinates")))
            .add("officialAddress",Json.createObjectBuilder().add("id",referenceId(original,"addresses")));
        if(original.get("postalAddress") instanceof JsonObject postal) body.add("postalAddress",Json.createObjectBuilder().add("id",postal.getInt("id")));
        return body;
    }
    static long version(JsonObject o){return Long.parseLong(o.getString("version"));}
    void track(JsonObject o){organizations.add(o.getInt("id"));coordinates.add(o.getJsonObject("coordinates").getInt("id"));var a=o.getJsonObject("officialAddress");addresses.add(a.getInt("id"));locations.add(a.getJsonObject("town").getInt("id"));}
    JsonObject get(int id) throws Exception{return ok("GET","/api/organizations/"+id,null,null);}
    JsonObjectBuilder mergeBody(JsonObject first,JsonObject second,String name) {
        return Json.createObjectBuilder().add("firstOrganizationId",first.getInt("id"))
                .add("secondOrganizationId",second.getInt("id")).add("name",name)
                .add("officialAddress",Json.createObjectBuilder().add("id",referenceId(first,"addresses")));
    }
    Set<Integer> referenceIds(String kind) throws Exception {
        var response=send("GET","/api/reference/"+kind,null,null);
        assertEquals(200,response.statusCode(),response.body());
        try(var reader=Json.createReader(new java.io.StringReader(response.body()))) {
            Set<Integer> ids=new HashSet<>();
            for(var value:reader.readArray())ids.add(value.asJsonObject().getInt("id"));
            return ids;
        }
    }
    @AfterEach void cleanup() throws Exception {
        if(client==null||csrf==null)return;
        for(int id:organizations)send("DELETE","/api/organizations/"+id,null,null);
        for(var group:List.of(Map.entry("addresses",addresses),Map.entry("coordinates",coordinates),Map.entry("locations",locations)))
            for(int id:group.getValue())send("DELETE","/api/reference/"+group.getKey()+"/"+id,null,null);
        send("POST","/api/auth/logout",null,null);
    }
    @Test void strictNumbersJsonAndLiteralSearch() throws Exception {
        String name="%_!api-"+UUID.randomUUID();var o=create(name,Long.toString(Long.MAX_VALUE));
        assertEquals(Long.toString(Long.MAX_VALUE),o.getString("employeesCount"));
        assertEquals(Long.toString(Long.MAX_VALUE),get(o.getInt("id")).getString("employeesCount"));
        for(String bad:List.of(fixture("bad","1").replace("\"annualTurnover\":10","\"annualTurnover\":1e100"),fixture("bad","1").replace("\"rating\":1","\"rating\":1.5"),fixture("bad","9223372036854775808"),"{",fixture("bad","1").replace("COMMERCIAL","NO_SUCH_TYPE")))
            assertEquals(400,send("POST","/api/organizations",bad,null).statusCode(),bad);
        String term=URLEncoder.encode(name,java.nio.charset.StandardCharsets.UTF_8);
        var list=ok("GET","/api/organizations?filterValue="+term,null,null);
        assertEquals(1,list.getJsonNumber("total").longValue());
        var prefix=send("GET","/api/special-operations/name-starts-with?prefix="+term,null,null);
        assertEquals(200,prefix.statusCode());assertTrue(prefix.body().contains(name));
        // Compare committed GET snapshots: PostgreSQL timestamps have microsecond precision.
        o=get(o.getInt("id"));
        var overflow=send("POST","/api/special-operations/"+o.getInt("id")+"/add-employee",null,null);
        assertEquals(409,overflow.statusCode(),overflow.body());
        assertTrue(parse(overflow.body()).getString("error").contains("employeesCount"),overflow.body());
        assertEquals(o,get(o.getInt("id")),"Overflow must not change employeesCount or version");
    }
    @Test void allReferenceReplacements() throws Exception {
        var a=create("source","1");var b=create("replacement","1");int id=a.getInt("id");
        // Exercise both address roles without double-incrementing the same row.
        ok("PUT","/api/organizations/"+id,updateBody(a,"source").add("postalAddress",Json.createObjectBuilder().add("id",referenceId(a,"addresses"))).build().toString(),null);
        for(String kind:List.of("locations","addresses","coordinates")){
            var before=get(id);
            int oldId=referenceId(before,kind),newId=referenceId(b,kind);
            assertTrue(ok("GET","/api/reference/"+kind+"/"+oldId+"/usage",null,null).getJsonNumber("totalReferences").longValue()>0);
            assertEquals(400,send("DELETE","/api/reference/"+kind+"/"+oldId,null,null).statusCode());
            assertEquals(204,send("DELETE","/api/reference/"+kind+"/"+oldId+"?replacementId="+newId,null,null).statusCode());
            var after=get(id);
            assertEquals(newId,referenceId(after,kind));
            assertEquals(version(before)+1,version(after),kind);
            assertEquals(version(b),version(get(b.getInt("id"))),"unrelated organization must retain its version");
            assertEquals(409,send("PUT","/api/organizations/"+id,updateBody(before,"stale").build().toString(),null).statusCode(),kind);
            assertEquals("source",get(id).getString("name"));
        }
    }
    @Test void postalOnlyLocationReplacementInvalidatesVersion() throws Exception {
        var a=create("postal source","1");var b=create("official replacement","1");int id=a.getInt("id");
        var before=ok("PUT","/api/organizations/"+id,updateBody(a,"postal source")
            .add("postalAddress",Json.createObjectBuilder().add("id",referenceId(a,"addresses")))
            .add("officialAddress",Json.createObjectBuilder().add("id",referenceId(b,"addresses"))).build().toString(),null);
        int oldTown=referenceId(a,"locations"),newTown=referenceId(b,"locations");
        assertEquals(204,send("DELETE","/api/reference/locations/"+oldTown+"?replacementId="+newTown,null,null).statusCode());
        var after=get(id);
        assertEquals(newTown,after.getJsonObject("postalAddress").getJsonObject("town").getInt("id"));
        assertEquals(version(before)+1,version(after));
        assertEquals(version(b),version(get(b.getInt("id"))));
        assertEquals(409,send("PUT","/api/organizations/"+id,updateBody(before,"stale postal").build().toString(),null).statusCode());
    }
    static int referenceId(JsonObject o,String kind){return switch(kind){case "coordinates"->o.getJsonObject("coordinates").getInt("id");case "addresses"->o.getJsonObject("officialAddress").getInt("id");default->o.getJsonObject("officialAddress").getJsonObject("town").getInt("id");};}
    @Test void putRequiresOriginalVersionAndAcceptsCurrentVersion() throws Exception {
        var o=create("editable","4");int id=o.getInt("id");String route="/api/organizations/"+id;
        var missing=updateBody(o,"missing");missing.remove("version");
        var rejected=send("PUT",route,missing.build().toString(),null);
        assertEquals(400,rejected.statusCode(),rejected.body());assertTrue(rejected.body().contains("version"));
        assertEquals(400,send("PUT",route,updateBody(o,"null").addNull("version").build().toString(),null).statusCode());
        assertEquals(400,send("PUT",route,updateBody(o,"negative").add("version","-1").build().toString(),null).statusCode());
        assertEquals(version(o),version(get(id)));
        var updated=ok("PUT",route,updateBody(o,"saved").build().toString(),null);
        assertEquals("saved",updated.getString("name"));assertEquals(version(o)+1,version(updated));
        assertEquals(updated,get(id));
        var again=ok("PUT",route,updateBody(updated,"saved again").build().toString(),null);
        assertEquals(version(updated)+1,version(again));
    }
    @Test void stalePutAfterAddEmployeeNeverRevertsEmployees() throws Exception {
        var original=create("employee edit","7");int id=original.getInt("id");
        var incremented=ok("POST","/api/special-operations/"+id+"/add-employee",null,null);
        assertEquals("8",incremented.getString("employeesCount"));assertEquals(version(original)+1,version(incremented));
        var stale=send("PUT","/api/organizations/"+id,updateBody(original,"obsolete form").build().toString(),null);
        assertEquals(409,stale.statusCode(),stale.body());assertTrue(parse(stale.body()).getString("error").contains("Reload"));
        assertFalse(stale.body().contains("UPDATE "));assertFalse(stale.body().contains("Exception"));
        assertEquals("8",get(id).getString("employeesCount"));assertEquals("employee edit",get(id).getString("name"));
        // Freshly reloaded DTOs still update normally, preserving the increment.
        var saved=ok("PUT","/api/organizations/"+id,updateBody(incremented,"fresh form").build().toString(),null);
        assertEquals("8",saved.getString("employeesCount"));assertEquals(version(incremented)+1,version(saved));
    }
    @Test void concurrentPutsWithSameVersionHaveOneWinnerAndStalePutsConflict() throws Exception {
        var original=create("concurrent edit","1");int id=original.getInt("id");String route="/api/organizations/"+id;
        var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try {
            List<Future<HttpResponse<String>>> responses=new ArrayList<>();
            for(String name:List.of("first writer","second writer")) {
                String body=updateBody(original,name).build().toString();
                responses.add(pool.submit(()->{start.await();return send("PUT",route,body,null);}));
            }
            start.countDown();List<Integer> statuses=new ArrayList<>();JsonObject winner=null;
            for(var response:responses){var r=response.get(30,TimeUnit.SECONDS);statuses.add(r.statusCode());if(r.statusCode()==200)winner=parse(r.body());}
            Collections.sort(statuses);assertEquals(List.of(200,409),statuses);
            assertNotNull(winner);assertEquals(winner,get(id));assertEquals(version(original)+1,version(winner));
            // Both already-stale requests must conflict, even when submitted together.
            List<Callable<HttpResponse<String>>> stale=new ArrayList<>();
            for(int i=0;i<2;i++)stale.add(()->send("PUT",route,updateBody(original,"stale").build().toString(),null));
            for(var response:pool.invokeAll(stale))assertEquals(409,response.get().statusCode());
            assertEquals(winner,get(id));
        } finally {pool.shutdownNow();}
    }
    @Test void concurrentCreateAndMergeKeys() throws Exception {
        var pool=Executors.newFixedThreadPool(6);
        try {
            String key=UUID.randomUUID().toString(),body=fixture("concurrent","1");
            List<Callable<JsonObject>> calls=new ArrayList<>();for(int i=0;i<8;i++)calls.add(()->ok("POST","/api/organizations",body,key));
            Set<Integer> ids=new HashSet<>();for(var future:pool.invokeAll(calls)){var o=future.get();track(o);ids.add(o.getInt("id"));}assertEquals(1,ids.size());
            var a=get(ids.iterator().next());var b=create("second","2");
            String mergedName="merged-"+UUID.randomUUID(),merge=mergeBody(a,b,mergedName).build().toString();
            String mergeKey=UUID.randomUUID().toString();calls.clear();for(int i=0;i<8;i++)calls.add(()->ok("POST","/api/special-operations/merge",merge,mergeKey));
            ids.clear();for(var future:pool.invokeAll(calls)){var o=future.get();track(o);ids.add(o.getInt("id"));assertEquals("3",o.getString("employeesCount"));assertEquals("1",o.getString("version"));}assertEquals(1,ids.size());
            int mergedId=ids.iterator().next();
            assertNotEquals(a.getInt("id"),mergedId);assertNotEquals(b.getInt("id"),mergedId);
            var current=get(mergedId);
            assertEquals(mergedId,current.getInt("id"));assertEquals(mergedName,current.getString("name"));
            assertEquals("3",current.getString("employeesCount"));assertEquals("1",current.getString("version"));
            for(var original:List.of(a,b)) {
                var missing=send("GET","/api/organizations/"+original.getInt("id"),null,null);
                assertEquals(404,missing.statusCode(),missing.body());
            }
            // Moving the employees deletes source organizations, never shared reference objects.
            for(String kind:List.of("coordinates","addresses","locations")) {
                var remaining=referenceIds(kind);
                for(var original:List.of(a,b))assertTrue(remaining.contains(referenceId(original,kind)),kind);
            }
            var freshRetry=send("POST","/api/special-operations/merge",merge,UUID.randomUUID().toString());
            assertEquals(404,freshRetry.statusCode(),freshRetry.body());
            assertEquals(current,get(mergedId),"A fresh-key retry of deleted sources must not alter the result");
            String term=URLEncoder.encode(mergedName,java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(1,ok("GET","/api/organizations?filterValue="+term,null,null).getJsonNumber("total").longValue());
        } finally{pool.shutdownNow();}
    }
    @Test void mergeOverflowsReportFieldAndLeaveBothSourcesUnchanged() throws Exception {
        for(String field:List.of("employeesCount","annualTurnover")) {
            var first=create("overflow first "+field,field.equals("employeesCount")?Long.toString(Long.MAX_VALUE):"1");
            var second=create("overflow second "+field,"1");
            if(field.equals("annualTurnover")) {
                first=ok("PUT","/api/organizations/"+first.getInt("id"),
                        updateBody(first,first.getString("name")).add("annualTurnover",3e38).build().toString(),null);
                second=ok("PUT","/api/organizations/"+second.getInt("id"),
                        updateBody(second,second.getString("name")).add("annualTurnover",3e38).build().toString(),null);
            }
            // Baseline the stored rows, not pre-commit POST DTO timestamp precision.
            // Keep full DTO equality below, including creationDate and version.
            first=get(first.getInt("id"));second=get(second.getInt("id"));
            Map<String,Set<Integer>> referencesBefore=new HashMap<>();
            for(String kind:List.of("coordinates","addresses","locations"))referencesBefore.put(kind,referenceIds(kind));
            String mergedName="overflow result "+UUID.randomUUID();
            // New nested references also must not survive a rolled-back merge.
            var newAddress=Json.createObjectBuilder().add("zipCode","rollback")
                    .add("town",Json.createObjectBuilder().add("name","rollback town").add("x",1).add("y",2).add("z",3));
            String body=mergeBody(first,second,mergedName).add("officialAddress",newAddress).build().toString();
            var overflow=send("POST","/api/special-operations/merge",body,UUID.randomUUID().toString());
            assertEquals(409,overflow.statusCode(),overflow.body());
            assertTrue(parse(overflow.body()).getString("error").contains(field),overflow.body());
            assertFalse(overflow.body().contains("UPDATE "));assertFalse(overflow.body().contains("Exception"));
            assertEquals(first,get(first.getInt("id")),"First source must retain all fields and its version: "+field);
            assertEquals(second,get(second.getInt("id")),"Second source must retain all fields and its version: "+field);
            for(String kind:referencesBefore.keySet())assertEquals(referencesBefore.get(kind),referenceIds(kind),kind);
            String term=URLEncoder.encode(mergedName,java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(0,ok("GET","/api/organizations?filterValue="+term,null,null).getJsonNumber("total").longValue());
        }
    }
    @Test void concurrentEmployeeUpdatesAndRetryKey() throws Exception {
        var o=create("employees","1");int id=o.getInt("id");
        String route="/api/special-operations/"+id+"/add-employee";
        var pool=Executors.newFixedThreadPool(6);
        try {
            List<Callable<JsonObject>> calls=new ArrayList<>();
            for(int i=0;i<8;i++) calls.add(()->ok("POST",route,null,UUID.randomUUID().toString()));
            for(var future:pool.invokeAll(calls))future.get();
            assertEquals("9",get(id).getString("employeesCount"));
            assertEquals(version(o)+8,version(get(id)));
            String key=UUID.randomUUID().toString();
            assertEquals("10",ok("POST",route,null,key).getString("employeesCount"));
            assertEquals("10",ok("POST",route,null,key).getString("employeesCount"));
            assertEquals("10",get(id).getString("employeesCount"));
        } finally {pool.shutdownNow();}
    }
    @Test void authenticationCsrfWebsocketAndLogout() throws Exception {
        var anonymous=HttpClient.newHttpClient();
        assertEquals(401,anonymous.send(HttpRequest.newBuilder(URI.create(base+"/api/organizations")).build(),HttpResponse.BodyHandlers.ofString()).statusCode());
        String saved=csrf;csrf=null;assertEquals(403,send("POST","/api/organizations",fixture("blocked","1"),null).statusCode());csrf=saved;
        var events=new LinkedBlockingQueue<String>();var closed=new CompletableFuture<Integer>();
        var ws=client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10)).buildAsync(URI.create(base.replaceFirst("^http","ws")+"/ws/organizations?token="+wsToken),new WebSocket.Listener(){
            StringBuilder buffer=new StringBuilder();
            public void onOpen(WebSocket socket){socket.request(1);}
            public CompletionStage<?> onText(WebSocket socket,CharSequence text,boolean last){buffer.append(text);if(last){events.add(buffer.toString());buffer.setLength(0);}socket.request(1);return null;}
            public CompletionStage<?> onClose(WebSocket socket,int code,String reason){closed.complete(code);return null;}
        }).get(15,TimeUnit.SECONDS);
        try{
            var o=create("websocket","1");String event=events.poll(10,TimeUnit.SECONDS);assertNotNull(event);assertEquals(o.getInt("id"),parse(event).getInt("id"));
            // Event must refer to a committed object visible through a fresh HTTP request.
            assertEquals(o.getInt("id"),get(o.getInt("id")).getInt("id"));
            cleanup();organizations.clear();addresses.clear();locations.clear();coordinates.clear();csrf=null;
            assertEquals(1008,closed.get(10,TimeUnit.SECONDS));
            assertEquals(401,send("GET","/api/organizations",null,null).statusCode());
        }finally{ws.abort();}
    }
}
