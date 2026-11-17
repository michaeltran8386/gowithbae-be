package vn.gowithbae.me;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import vn.gowithbae.api.model.PlaceVisitCount;
import vn.gowithbae.common.ActorResolver;
import vn.gowithbae.common.ActorResolver.UserPrincipal;

/** Đếm số lần tự xác nhận đã đi (YES hợp lệ) theo tài khoản + chi nhánh (UC-027, FR-35, RULE-29). */
@Service
public class VisitCountService {

    private final JdbcClient jdbc;
    private final ActorResolver actorResolver;

    public VisitCountService(JdbcClient jdbc, ActorResolver actorResolver) {
        this.jdbc = jdbc;
        this.actorResolver = actorResolver;
    }

    public PlaceVisitCount getMyPlaceVisitCount(UUID placeId) {
        UserPrincipal user = actorResolver.requireActiveUser();

        Boolean placeExists = jdbc.sql("""
                SELECT EXISTS (
                    SELECT 1 FROM place
                    WHERE id = :placeId AND status IN ('PUBLISHED', 'HIDDEN', 'INACTIVE')
                )
                """)
                .param("placeId", placeId)
                .query(Boolean.class)
                .single();
        if (!Boolean.TRUE.equals(placeExists)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Địa điểm không tồn tại");
        }

        Long count = jdbc.sql("""
                SELECT COUNT(DISTINCT vsr.plan_id)
                FROM visit_self_report vsr
                JOIN date_plan dp ON dp.id = vsr.plan_id
                WHERE vsr.respondent_user_id = :userId
                  AND dp.place_id = :placeId
                  AND dp.status <> 'CANCELLED'
                  AND vsr.answer = 'YES'
                """)
                .param("userId", user.userId())
                .param("placeId", placeId)
                .query(Long.class)
                .single();

        return new PlaceVisitCount(placeId, count == null ? 0L : count);
    }
}
