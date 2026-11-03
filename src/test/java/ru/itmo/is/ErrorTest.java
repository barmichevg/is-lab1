package ru.itmo.is;

import jakarta.persistence.OptimisticLockException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import ru.itmo.is.exception.*;

import java.sql.SQLException;
import java.time.Duration;
import java.util.*;
import java.util.logging.*;

import static org.junit.jupiter.api.Assertions.*;

class ErrorTest {
    private static final String PRIVATE = "UPDATE secret_table SET password='secret'; private SQL";
    private final ApiExceptionMapper mapper = new ApiExceptionMapper();

    private static String message(Response response) {
        return (String) ((Map<?, ?>) response.getEntity()).get("error");
    }

    private static void assertSafe(Response response) {
        String body = response.getEntity().toString();
        for (String secret : List.of("secret", "UPDATE", "private", "password", "DETAIL", "HINT", "SQLException", "PSQLException")) {
            assertFalse(body.contains(secret), body);
        }
    }

    private static PSQLException postgres(String state, String constraint) {
        // PostgreSQL protocol fields: message, detail, hint and constraint name.
        return new PSQLException(new ServerErrorMessage("SERROR\0C" + state + "\0M" + PRIVATE
                + "\0DDETAIL secret\0HHINT password=secret\0n" + constraint + "\0\0"));
    }

    @Test void internalDetailsNeverLeak() {
        var response = mapper.toResponse(new IllegalStateException("password=secret"));
        assertEquals(500, response.getStatus());
        assertEquals("Internal server error", message(response));
        assertSafe(response);
    }

    @Test void optimisticConflictsAreSafeIncludingTransactionWrappers() {
        var failure = new OptimisticLockException(PRIVATE, new SQLException(PRIVATE, "23503"));
        for (Throwable error : List.of(failure,
                new jakarta.persistence.RollbackException(PRIVATE, failure),
                new jakarta.transaction.TransactionalException(PRIVATE, failure))) {
            var response = mapper.toResponse(error);
            assertEquals(409, response.getStatus());
            assertTrue(message(response).contains("Reload"));
            assertSafe(response);
            assertFalse(response.getEntity().toString().contains("23503"));
        }
    }

    @Test void clientErrorsAndConflict() {
        var validation = mapper.toResponse(new ApiValidationException(List.of("rating: integer required")));
        assertEquals(400, validation.getStatus());
        assertEquals(List.of("rating: integer required"), ((Map<?, ?>) validation.getEntity()).get("details"));
        assertEquals(400, mapper.toResponse(new jakarta.ws.rs.BadRequestException()).getStatus());
        assertEquals(404, mapper.toResponse(new NotFoundException("Organization not found")).getStatus());
        assertEquals(409, mapper.toResponse(new RuntimeException(new SQLException(PRIVATE, "22003"))).getStatus());
    }

    @Test void customStatesDescribeSpecificOperationAndNeverExposeServerText() {
        Map<String, Integer> statuses = Map.of("P1001", 409, "P1002", 409, "P1003", 409,
                "P1004", 409, "P1005", 404, "P1006", 409, "P1007", 400);
        Map<String, String> expected = Map.of(
                "P1001", "Нельзя добавить сотрудника: employeesCount достиг максимума (9223372036854775807).",
                "P1002", "Объединение невозможно: сумма employeesCount превышает максимум (9223372036854775807).",
                "P1003", "Объединение невозможно: сумма annualTurnover превышает диапазон float.",
                "P1004", "Изменение невозможно: счётчик version исчерпан. Обратитесь к администратору.",
                "P1005", "Организация не найдена или уже удалена. Обновите список организаций.",
                "P1006", "Выбранный адрес или город не найден либо уже удалён. Обновите справочники и выберите объект заново.",
                "P1007", "Некорректные параметры объединения. Выберите две разные организации и проверьте имя и адрес новой организации.");
        for (String state : statuses.keySet()) {
            for (SQLException sql : List.of(new SQLException(PRIVATE, state), postgres(state, "private_constraint"))) {
                var response = mapper.toResponse(new jakarta.persistence.RollbackException(PRIVATE, sql));
                assertEquals(statuses.get(state).intValue(), response.getStatus(), state);
                assertEquals(expected.get(state), message(response));
                assertFalse(response.getEntity().toString().contains(state));
                assertSafe(response);
            }
        }
    }

    @Test void genericSqlStatesHaveFixedDistinctSafeMessages() {
        Map<String, String> expected = Map.ofEntries(
                Map.entry("22003", "Числовое значение или результат операции выходит за допустимый диапазон. Проверьте числовые поля."),
                Map.entry("22001", "Строковое значение превышает допустимую длину. Сократите введённый текст."),
                Map.entry("23503", "Связанный объект отсутствует или ещё используется. Обновите данные и проверьте выбранные связи."),
                Map.entry("23502", "Не заполнено обязательное поле. Проверьте данные объекта."),
                Map.entry("23514", "Значение поля не соответствует ограничениям. Проверьте данные объекта."),
                Map.entry("23505", "Объект с таким уникальным значением уже существует. Обновите данные и проверьте значения."),
                Map.entry("40P01", "Операция отменена из-за взаимной блокировки параллельных изменений. Повторите операцию."),
                Map.entry("40001", "Операция конфликтует с параллельным изменением данных. Обновите данные и повторите операцию."),
                Map.entry("55P03", "Нужные данные сейчас заблокированы другой операцией. Повторите попытку позже."));
        for (var entry : expected.entrySet()) {
            var response = mapper.toResponse(new SQLException(PRIVATE, entry.getKey()));
            assertEquals(409, response.getStatus());
            assertEquals(entry.getValue(), message(response));
            assertFalse(response.getEntity().toString().contains(entry.getKey()));
            assertSafe(response);
        }
        assertEquals(expected.size(), new HashSet<>(expected.values()).size());
    }

    @Test void onlyWhitelistedStructuredConstraintNamesAreUsed() {
        Map<String, String> checks = Map.of(
                "ck_organizations_employees_positive", "employeesCount должен быть больше 0.",
                "ck_organizations_turnover_positive", "annualTurnover должен быть больше 0.",
                "ck_organizations_rating_positive", "rating должен быть больше 0.",
                "ck_locations_name_not_blank", "Название города не может быть пустым.",
                "ck_turnover_finite", "annualTurnover должен быть конечным числом в диапазоне float.",
                "ck_location_finite", "Координаты x и y города должны быть конечными числами в диапазоне Double.");
        for (var entry : checks.entrySet()) {
            var response = mapper.toResponse(postgres("23514", entry.getKey()));
            assertEquals(409, response.getStatus());
            assertEquals(entry.getValue(), message(response));
            assertFalse(response.getEntity().toString().contains(entry.getKey()));
            assertSafe(response);
        }
        var foreignKey = mapper.toResponse(postgres("23503", "addresses_town_id_fkey"));
        assertEquals("Город отсутствует или ещё используется адресом. Обновите справочники и проверьте связи.", message(foreignKey));
        assertSafe(foreignKey);
    }

    @Test void unknownConstraintAndMessageSpoofingUseGenericMessage() {
        var generic = mapper.toResponse(new SQLException(PRIVATE, "23514"));
        for (SQLException sql : List.of(postgres("23514", "private_constraint_password_secret"),
                new SQLException("ck_organizations_employees_positive " + PRIVATE, "23514"))) {
            var response = mapper.toResponse(sql);
            assertEquals(message(generic), message(response));
            assertSafe(response);
        }
        // A recognised name with a different SQLSTATE must not select a check message.
        var mismatch = mapper.toResponse(postgres("23505", "ck_organizations_employees_positive"));
        assertEquals(message(mapper.toResponse(new SQLException(PRIVATE, "23505"))), message(mismatch));
        assertSafe(mismatch);
    }

    @Test void customReasonInNextExceptionWinsOverGenericWrapper() {
        var batch = new SQLException(PRIVATE, "22003");
        var intermediary = new SQLException(PRIVATE); // null SQLSTATE is valid JDBC input
        batch.setNextException(intermediary);
        intermediary.setNextException(new SQLException(PRIVATE, "P1002"));
        var response = mapper.toResponse(new RuntimeException(batch));
        assertEquals(409, response.getStatus());
        assertTrue(message(response).contains("сумма employeesCount"));
        assertSafe(response);
    }

    @Test void knownConstraintInSqlChainWinsOverGenericOuterState() {
        var wrapper = new SQLException(PRIVATE, "23000");
        wrapper.setNextException(postgres("23514", "ck_organizations_rating_positive"));
        var response = mapper.toResponse(wrapper);
        assertEquals(409, response.getStatus());
        assertEquals("rating должен быть больше 0.", message(response));
        assertSafe(response);
    }

    @Test void sqlCauseAndNextExceptionGraphsAreTraversedWithoutCycles() {
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            var root = new SQLException(PRIVATE);
            var detail = new SQLException(PRIVATE, "P1005");
            root.setNextException(detail);
            detail.setNextException(root);
            var wrapper = new RuntimeException(PRIVATE, root);
            root.initCause(wrapper); // cycle across ordinary causes and SQL chains
            detail.initCause(root);
            var response = mapper.toResponse(wrapper);
            assertEquals(404, response.getStatus());
            assertSafe(response);
        });
    }

    @Test void optimisticConflictHasPriorityOverSqlAndCustomStatesAnywhereInGraph() {
        var outer = new SQLException(PRIVATE, "23503");
        var application = new SQLException(PRIVATE, "P1005");
        outer.setNextException(application);
        application.initCause(new RuntimeException(PRIVATE, new OptimisticLockException(PRIVATE)));
        var response = mapper.toResponse(outer);
        assertEquals(409, response.getStatus());
        assertTrue(message(response).contains("Reload"));
        assertSafe(response);
    }

    @Test void unknownNullAndMalformedStatesDoNotLeakOrGuessCustomErrors() {
        for (String state : Arrays.asList(null, "", "P1008", "P1001secret", "p1001", "XX000", "secret")) {
            var response = mapper.toResponse(new SQLException(PRIVATE, state));
            assertEquals(500, response.getStatus(), String.valueOf(state));
            assertEquals("Internal server error", message(response));
            assertSafe(response);
        }
        var unknownData = mapper.toResponse(new SQLException(PRIVATE, "22999"));
        assertEquals(409, unknownData.getStatus());
        assertSafe(unknownData);
        var legacy = mapper.toResponse(new SQLException(PRIVATE, "P0001"));
        assertEquals(409, legacy.getStatus());
        assertSafe(legacy);
    }

    @Test void unknownWrappedSqlDoesNotLeakThroughFallbackLoggingEither() {
        var records = new ArrayList<LogRecord>();
        var logger = Logger.getLogger(ApiExceptionMapper.class.getName());
        Handler capture = new Handler() {
            @Override public void publish(LogRecord record) { records.add(record); }
            @Override public void flush() {}
            @Override public void close() {}
        };
        logger.addHandler(capture);
        try {
            var response = mapper.toResponse(new RuntimeException(PRIVATE, postgres("XX000", "private_constraint")));
            assertEquals(500, response.getStatus());
            assertSafe(response);
            assertFalse(records.isEmpty());
            for (LogRecord record : records) {
                assertNull(record.getThrown(), "Raw exception must not reach a logging formatter");
                assertFalse(record.getMessage().contains("secret"));
                if (record.getParameters() != null) {
                    assertFalse(Arrays.toString(record.getParameters()).contains("secret"));
                    assertFalse(Arrays.toString(record.getParameters()).contains("UPDATE"));
                }
            }
        } finally {
            logger.removeHandler(capture);
        }
    }
}
