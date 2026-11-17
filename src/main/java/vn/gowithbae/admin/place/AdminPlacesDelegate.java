package vn.gowithbae.admin.place;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import vn.gowithbae.admin.AdminPermissions;
import vn.gowithbae.api.AdminPlacesApiDelegate;
import vn.gowithbae.api.model.AdminPlace;
import vn.gowithbae.api.model.AdminPlacePage;
import vn.gowithbae.api.model.AdminPlaceWrite;
import vn.gowithbae.api.model.AdminTransitionPlaceRequest;
import vn.gowithbae.api.model.AdminTransitionPlaceRequest.ActionEnum;
import vn.gowithbae.api.model.PlaceImage;
import vn.gowithbae.api.model.PlaceImageWrite;
import vn.gowithbae.api.model.PlaceStatus;

/** Quản trị địa điểm (FR-23, FR-24). Kiểm tra vai trò ở đây theo {@link AdminPermissions}, nghiệp vụ ở service. */
@Service
public class AdminPlacesDelegate implements AdminPlacesApiDelegate {

    private final AdminPlaceService service;

    public AdminPlacesDelegate(AdminPlaceService service) {
        this.service = service;
    }

    @Override
    public ResponseEntity<AdminPlacePage> adminListPlaces(PlaceStatus status, UUID areaId, String q, Integer page,
            Integer size) {
        AdminPermissions.requireContentRead();
        return ResponseEntity.ok(service.list(status, areaId, q, page == null ? 0 : page, size == null ? 20 : size));
    }

    @Override
    public ResponseEntity<AdminPlace> adminGetPlace(UUID placeId) {
        AdminPermissions.requireContentRead();
        return ResponseEntity.ok(service.get(placeId));
    }

    @Override
    public ResponseEntity<AdminPlace> adminCreatePlace(AdminPlaceWrite body) {
        AdminPermissions.requireContentEdit();
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(body));
    }

    @Override
    public ResponseEntity<AdminPlace> adminUpdatePlace(UUID placeId, AdminPlaceWrite body) {
        AdminPermissions.requireContentEdit();
        return ResponseEntity.ok(service.update(placeId, body));
    }

    /** Gửi duyệt là việc biên tập; mọi chuyển trạng thái khác là việc duyệt (RULE-21). */
    @Override
    public ResponseEntity<AdminPlace> adminTransitionPlace(UUID placeId, AdminTransitionPlaceRequest body) {
        if (body.getAction() == ActionEnum.SUBMIT) {
            AdminPermissions.requireContentEdit();
        } else {
            AdminPermissions.requireContentReview();
        }
        return ResponseEntity.ok(service.transition(placeId, body.getAction(), body.getReason()));
    }

    @Override
    public ResponseEntity<PlaceImage> adminAddPlaceImage(UUID placeId, PlaceImageWrite body) {
        AdminPermissions.requireContentEdit();
        return ResponseEntity.status(HttpStatus.CREATED).body(service.addImage(placeId, body));
    }

    @Override
    public ResponseEntity<Void> adminRemovePlaceImage(UUID placeId, UUID imageId) {
        AdminPermissions.requireContentEdit();
        service.removeImage(placeId, imageId);
        return ResponseEntity.noContent().build();
    }
}
