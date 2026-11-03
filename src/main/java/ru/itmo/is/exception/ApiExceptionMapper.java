package ru.itmo.is.exception;

import jakarta.persistence.OptimisticLockException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.*;
import jakarta.ws.rs.ext.*;
import org.postgresql.util.PSQLException;

import java.sql.SQLException;
import java.util.*;
import java.util.logging.*;

@Provider
public class ApiExceptionMapper implements ExceptionMapper<Throwable> {
    private static final Logger LOG = Logger.getLogger(ApiExceptionMapper.class.getName());

    // Only application-owned SQLSTATEs and fixed messages are exposed to clients.
    private static final Map<String, SqlProblem> APPLICATION_ERRORS = Map.ofEntries(
            problem("P1001", 409, "Нельзя добавить сотрудника: employeesCount достиг максимума (9223372036854775807)."),
            problem("P1002", 409, "Объединение невозможно: сумма employeesCount превышает максимум (9223372036854775807)."),
            problem("P1003", 409, "Объединение невозможно: сумма annualTurnover превышает диапазон float."),
            problem("P1004", 409, "Изменение невозможно: счётчик version исчерпан. Обратитесь к администратору."),
            problem("P1005", 404, "Организация не найдена или уже удалена. Обновите список организаций."),
            problem("P1006", 409, "Выбранный адрес или город не найден либо уже удалён. Обновите справочники и выберите объект заново."),
            problem("P1007", 400, "Некорректные параметры объединения. Выберите две разные организации и проверьте имя и адрес новой организации.")
    );

    private static final Map<String, SqlProblem> SQL_ERRORS = Map.ofEntries(
            problem("22003", 409, "Числовое значение или результат операции выходит за допустимый диапазон. Проверьте числовые поля."),
            problem("22001", 409, "Строковое значение превышает допустимую длину. Сократите введённый текст."),
            problem("23503", 409, "Связанный объект отсутствует или ещё используется. Обновите данные и проверьте выбранные связи."),
            problem("23502", 409, "Не заполнено обязательное поле. Проверьте данные объекта."),
            problem("23514", 409, "Значение поля не соответствует ограничениям. Проверьте данные объекта."),
            problem("23505", 409, "Объект с таким уникальным значением уже существует. Обновите данные и проверьте значения."),
            problem("40P01", 409, "Операция отменена из-за взаимной блокировки параллельных изменений. Повторите операцию."),
            problem("40001", 409, "Операция конфликтует с параллельным изменением данных. Обновите данные и повторите операцию."),
            problem("55P03", 409, "Нужные данные сейчас заблокированы другой операцией. Повторите попытку позже."),
            problem("P0001", 409, "Операция не может быть выполнена. Проверьте выбранные объекты и значения.")
    );

    private static final Map<String, String> CHECK_ERRORS = Map.ofEntries(
            Map.entry("ck_coordinates_id_positive", "ID координат должен быть больше 0."),
            Map.entry("ck_locations_id_positive", "ID города должен быть больше 0."),
            Map.entry("ck_addresses_id_positive", "ID адреса должен быть больше 0."),
            Map.entry("ck_organizations_id_positive", "ID организации должен быть больше 0."),
            Map.entry("ck_locations_name_not_blank", "Название города не может быть пустым."),
            Map.entry("ck_organizations_name_not_blank", "Название организации не может быть пустым."),
            Map.entry("ck_organizations_turnover_positive", "annualTurnover должен быть больше 0."),
            Map.entry("ck_organizations_employees_positive", "employeesCount должен быть больше 0."),
            Map.entry("ck_organizations_rating_positive", "rating должен быть больше 0."),
            Map.entry("ck_organizations_type_valid", "Выберите допустимый тип организации."),
            Map.entry("ck_turnover_finite", "annualTurnover должен быть конечным числом в диапазоне float."),
            Map.entry("ck_location_finite", "Координаты x и y города должны быть конечными числами в диапазоне Double.")
    );

    private static final Map<String, String> FOREIGN_KEY_ERRORS = Map.ofEntries(
            Map.entry("organizations_coordinates_id_fkey", "Выбранные координаты отсутствуют или ещё используются. Обновите справочники и проверьте связи."),
            Map.entry("organizations_official_address_id_fkey", "Официальный адрес отсутствует или ещё используется. Обновите справочники и проверьте связи."),
            Map.entry("organizations_postal_address_id_fkey", "Почтовый адрес отсутствует или ещё используется. Обновите справочники и проверьте связи."),
            Map.entry("addresses_town_id_fkey", "Город отсутствует или ещё используется адресом. Обновите справочники и проверьте связи.")
    );

    @Override public Response toResponse(Throwable exception) {
        List<Throwable> failures = failures(exception);
        // Inspect the whole graph first: a SQL wrapper must not mask an optimistic conflict.
        for (Throwable failure : failures) {
            if (failure instanceof OptimisticLockException
                    || failure.getClass().getName().equals("org.eclipse.persistence.exceptions.OptimisticLockException")) {
                return json(409, "Organization was changed by another operation. Reload it and retry your changes.", null);
            }
        }
        for (Throwable failure : failures) {
            if (failure instanceof ApiValidationException validation) return json(400, "Validation failed", validation.getErrors());
            if (failure instanceof NotFoundException) return json(404, failure.getMessage(), null);
        }
        // A driver/batch exception may carry a generic SQLSTATE while the chained
        // exception contains the application-specific reason. Prefer that reason.
        for (Throwable failure : failures) {
            if (failure instanceof SQLException sql) {
                String state = sql.getSQLState();
                SqlProblem problem = state == null ? null : APPLICATION_ERRORS.get(state);
                if (problem != null) return json(problem.status(), problem.message(), null);
            }
        }
        for (Throwable failure : failures) {
            if (failure instanceof SQLException sql) {
                SqlProblem problem = constraintProblem(sql);
                if (problem != null) return json(problem.status(), problem.message(), null);
            }
        }
        for (Throwable failure : failures) {
            if (failure instanceof SQLException sql) {
                SqlProblem problem = sqlProblem(sql);
                if (problem != null) return json(problem.status(), problem.message(), null);
            }
        }
        if (exception instanceof WebApplicationException web && web.getResponse().getStatus() < 500) {
            return json(web.getResponse().getStatus(), "Invalid request", null);
        }
        // Never log arbitrary throwable messages here: JDBC messages, SQL and
        // server detail/hint may contain user data or credentials, even when wrapped.
        LOG.log(Level.SEVERE, "API request failed ({0})", exception == null ? "null" : exception.getClass().getName());
        return json(500, "Internal server error", null);
    }

    private static List<Throwable> failures(Throwable root) {
        List<Throwable> result = new ArrayList<>();
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<Throwable> pending = new ArrayDeque<>();
        if (root != null) pending.add(root);
        while (!pending.isEmpty()) {
            Throwable failure = pending.removeFirst();
            if (!visited.add(failure)) continue;
            result.add(failure);
            Throwable cause = failure.getCause();
            if (cause != null) pending.addLast(cause);
            if (failure instanceof SQLException sql) {
                SQLException next = sql.getNextException();
                if (next != null) pending.addLast(next);
            }
        }
        return result;
    }

    private static SqlProblem constraintProblem(SQLException sql) {
        String state = sql.getSQLState();
        if (state == null) return null;
        if (sql instanceof PSQLException postgres && postgres.getServerErrorMessage() != null) {
            // Read only the constraint identifier; never expose the server message,
            // detail, hint, table/column names or an unrecognised identifier.
            String constraint = postgres.getServerErrorMessage().getConstraint();
            String message = constraint == null ? null : switch (state) {
                case "23514" -> CHECK_ERRORS.get(constraint);
                case "23503" -> FOREIGN_KEY_ERRORS.get(constraint);
                default -> null;
            };
            if (message != null) return new SqlProblem(409, message);
        }
        return null;
    }

    private static SqlProblem sqlProblem(SQLException sql) {
        String state = sql.getSQLState();
        if (state == null) return null;
        SqlProblem known = SQL_ERRORS.get(state);
        if (known != null) return known;
        // Keep unrecognised data/integrity errors safe without echoing SQLSTATE.
        if (state.startsWith("22") || state.startsWith("23")) {
            return new SqlProblem(409, "Операция нарушает ограничение данных. Проверьте значения полей и связи объектов.");
        }
        return null;
    }

    private record SqlProblem(int status, String message) {}

    private static Map.Entry<String, SqlProblem> problem(String state, int status, String message) {
        return Map.entry(state, new SqlProblem(status, message));
    }

    private Response json(int status, String message, Object details) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message);
        if (details != null) body.put("details", details);
        return Response.status(status).type(MediaType.APPLICATION_JSON_TYPE).entity(body).build();
    }
}
