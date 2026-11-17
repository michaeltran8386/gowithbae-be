package vn.gowithbae.support;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import vn.gowithbae.api.SupportApiDelegate;
import vn.gowithbae.api.model.MessageCreate;
import vn.gowithbae.api.model.SupportMessage;
import vn.gowithbae.api.model.SupportTicket;
import vn.gowithbae.api.model.SupportTicketCreate;
import vn.gowithbae.api.model.SupportTicketDetail;

/** REST delegate cho thành viên gửi, xem và bổ sung yêu cầu hỗ trợ. */
@Service
public class SupportDelegate implements SupportApiDelegate {

    private final SupportService supportService;

    public SupportDelegate(SupportService supportService) {
        this.supportService = supportService;
    }

    @Override
    public ResponseEntity<List<SupportTicket>> listMyTickets() {
        return ResponseEntity.ok(supportService.listMyTickets());
    }

    @Override
    public ResponseEntity<SupportTicketDetail> createTicket(SupportTicketCreate supportTicketCreate) {
        return ResponseEntity.status(HttpStatus.CREATED).body(supportService.createTicket(supportTicketCreate));
    }

    @Override
    public ResponseEntity<SupportTicketDetail> getMyTicket(UUID ticketId) {
        return ResponseEntity.ok(supportService.getMyTicket(ticketId));
    }

    @Override
    public ResponseEntity<SupportMessage> replyMyTicket(UUID ticketId, MessageCreate messageCreate) {
        return ResponseEntity.status(HttpStatus.CREATED).body(supportService.replyMyTicket(ticketId, messageCreate));
    }
}
