package vn.gowithbae.me;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import vn.gowithbae.api.MeApiDelegate;
import vn.gowithbae.api.model.PlaceVisitCount;

/**
 * Delegate cho /me (thuộc Người A sở hữu các endpoint hồ sơ, nhưng chứa UC-027 getMyPlaceVisitCount của Người C).
 * Người A chỉ cần bổ sung các method getMe/updateMe/... vào đây khi triển khai phần tài khoản.
 */
@Service
public class MeDelegate implements MeApiDelegate {

    private final VisitCountService visitCountService;

    public MeDelegate(VisitCountService visitCountService) {
        this.visitCountService = visitCountService;
    }

    @Override
    public ResponseEntity<PlaceVisitCount> getMyPlaceVisitCount(UUID placeId) {
        return ResponseEntity.ok(visitCountService.getMyPlaceVisitCount(placeId));
    }
}
