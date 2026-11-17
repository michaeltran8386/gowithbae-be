package vn.gowithbae.support;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import vn.gowithbae.api.AdminSupportApiDelegate;
import vn.gowithbae.api.model.AdminReplyTicketRequest;
import vn.gowithbae.api.model.AdminTransitionTicketRequest;
import vn.gowithbae.api.model.SupportMessage;
import vn.gowithbae.api.model.SupportTicketDetail;
import vn.gowithbae.api.model.SupportTicketPage;
import vn.gowithbae.api.model.TicketStatus;

/** REST delegate cho nhân sự hỗ trợ xử lý yêu cầu và ghi chú nội bộ. */
@Service
public class AdminSupportDelegate implements AdminSupportApiDelegate {

    private final SupportService supportService;

    public AdminSupportDelegate(SupportService supportService) {
        this.supportService = supportService;
    }

    @Override
    public ResponseEntity<SupportTicketPage> adminListTickets(
            TicketStatus status,
            UUID assigneeId,
            Integer page,
            Integer size) {
        return ResponseEntity.ok(supportService.adminListTickets(status, assigneeId, page, size));
    }

    @Override
    public ResponseEntity<SupportTicketDetail> adminGetTicket(UUID ticketId) {
        return ResponseEntity.ok(supportService.adminGetTicket(ticketId));
    }

    @Override
    public ResponseEntity<SupportMessage> adminReplyTicket(
            UUID ticketId,
            AdminReplyTicketRequest adminReplyTicketRequest) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(supportService.adminReplyTicket(ticketId, adminReplyTicketRequest));
    }

    @Override
    public ResponseEntity<SupportTicketDetail> adminTransitionTicket(
            UUID ticketId,
            AdminTransitionTicketRequest adminTransitionTicketRequest) {
        return ResponseEntity.ok(supportService.adminTransitionTicket(ticketId, adminTransitionTicketRequest));
    }
}
