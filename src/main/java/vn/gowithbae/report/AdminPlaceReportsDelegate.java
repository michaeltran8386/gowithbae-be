package vn.gowithbae.report;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import vn.gowithbae.api.AdminPlaceReportsApiDelegate;
import vn.gowithbae.api.model.AdminTransitionPlaceReportRequest;
import vn.gowithbae.api.model.PlaceReport;
import vn.gowithbae.api.model.PlaceReportPage;
import vn.gowithbae.api.model.PlaceReportStatus;

/** REST delegate cho hàng chờ và xử lý báo sai địa điểm phía quản trị. */
@Service
public class AdminPlaceReportsDelegate implements AdminPlaceReportsApiDelegate {

    private final PlaceReportService placeReportService;

    public AdminPlaceReportsDelegate(PlaceReportService placeReportService) {
        this.placeReportService = placeReportService;
    }

    @Override
    public ResponseEntity<PlaceReportPage> adminListPlaceReports(
            PlaceReportStatus status,
            UUID assigneeId,
            Integer page,
            Integer size) {
        return ResponseEntity.ok(placeReportService.adminListReports(status, assigneeId, page, size));
    }

    @Override
    public ResponseEntity<PlaceReport> adminGetPlaceReport(UUID reportId) {
        return ResponseEntity.ok(placeReportService.adminGetReport(reportId));
    }

    @Override
    public ResponseEntity<PlaceReport> adminTransitionPlaceReport(
            UUID reportId,
            AdminTransitionPlaceReportRequest adminTransitionPlaceReportRequest) {
        return ResponseEntity.ok(placeReportService.adminTransitionReport(reportId, adminTransitionPlaceReportRequest));
    }
}
