package app.giftify.payment.application;

import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import app.giftify.payment.adapter.outbound.pg.TossConfirmResult;
import app.giftify.payment.application.handler.WalletDeductedEventHandler;
import app.giftify.payment.application.inbound.ConfirmPaymentCommand;
import app.giftify.payment.application.outbound.PaymentFieldEncryptor;
import app.giftify.payment.application.outbound.PaymentGateway;
import app.giftify.payment.application.outbound.PaymentRepository;
import app.giftify.payment.domain.Payment;
import app.giftify.payment.domain.PaymentStatus;
import app.giftify.shared.domain.type.PaymentMethod;
import app.giftify.shared.domain.type.PaymentType;
import app.giftify.shared.domain.vo.Money;
import app.giftify.wallet.domain.event.WalletDeductedEvent;

@ExtendWith(MockitoExtension.class)
@DisplayName("복합결제(예치금 + PG) 흐름 (재현)")
class CompositePaymentFlowTest {

	@Mock
	private PaymentRepository paymentRepository;

	@Mock
	private PaymentModuleEventPublisher moduleEventPublisher;

	@Mock
	private PaymentGateway paymentGateway;

	@Mock
	private PaymentFieldEncryptor encryptor;

	private static final Long PAYMENT_ID = 1L;
	private static final Long MEMBER_ID = 100L;
	private static final Long WALLET_ID = 200L;
	private static final String ORDER_NUMBER = "ORDER-COMPOSITE-1";
	private static final Money TOTAL = Money.of(10000);
	private static final Money WALLET_PART = Money.of(3000);
	private static final Money PG_PART = Money.of(7000);

	private Payment compositePayment(PaymentStatus status) {
		return Payment.builder()
			.id(PAYMENT_ID)
			.memberId(MEMBER_ID)
			.orderId(123L)
			.orderNumber(ORDER_NUMBER)
			.type(PaymentType.FUNDING)
			.method(PaymentMethod.CARD)
			.originAmount(TOTAL)
			.paidAmount(TOTAL)
			.walletDeductedAmount(WALLET_PART)
			.status(status)
			.build();
	}

	@Test
	@DisplayName("예치금 차감만 끝난 복합결제는 PG 승인 전까지 PENDING 이어야 한다")
	void walletDeducted_CompositePayment_StaysPendingUntilPgConfirm() {
		// given
		WalletDeductedEventHandler handler = new WalletDeductedEventHandler(paymentRepository, moduleEventPublisher);
		WalletDeductedEvent event = new WalletDeductedEvent(
			WALLET_ID, MEMBER_ID, PAYMENT_ID, ORDER_NUMBER, WALLET_PART, LocalDateTime.now()
		);

		given(paymentRepository.findById(PAYMENT_ID)).willReturn(Optional.of(compositePayment(PaymentStatus.PENDING)));
		lenient().when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));

		// when
		handler.handle(event);

		// then
		verify(paymentRepository, never()).save(any(Payment.class));
		verify(moduleEventPublisher, never()).publishFrom(any(Payment.class), any(Payment.class));
	}

	@Test
	@DisplayName("이미 PAID 인 결제는 PG 승인을 호출하기 전에 거절되어야 한다")
	void confirm_AlreadyPaidPayment_DoesNotCallPg() {
		// given
		ConfirmPaymentService confirmPaymentService =
			new ConfirmPaymentService(paymentRepository, paymentGateway, encryptor, moduleEventPublisher);
		ConfirmPaymentCommand command = new ConfirmPaymentCommand(
			PAYMENT_ID, MEMBER_ID, "payment-key", ORDER_NUMBER, TOTAL
		);

		given(paymentRepository.findById(PAYMENT_ID)).willReturn(Optional.of(compositePayment(PaymentStatus.PAID)));
		lenient().when(paymentGateway.confirm(anyString(), anyString(), any()))
			.thenReturn(TossConfirmResult.success("payment-key", "txn-key", "12345678"));
		lenient().when(encryptor.encrypt(anyString())).thenReturn("encrypted");
		lenient().when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));

		// when
		catchThrowable(() -> confirmPaymentService.confirm(command));

		// then
		verify(paymentGateway, never()).confirm(anyString(), anyString(), any());
	}

	@Test
	@DisplayName("복합결제의 PG 승인 금액은 총액에서 예치금 차감분을 뺀 금액이어야 한다")
	void confirm_CompositePayment_ChargesOnlyPgPortion() {
		// given
		ConfirmPaymentService confirmPaymentService =
			new ConfirmPaymentService(paymentRepository, paymentGateway, encryptor, moduleEventPublisher);
		ConfirmPaymentCommand command = new ConfirmPaymentCommand(
			PAYMENT_ID, MEMBER_ID, "payment-key", ORDER_NUMBER, PG_PART
		);

		given(paymentRepository.findById(PAYMENT_ID)).willReturn(Optional.of(compositePayment(PaymentStatus.PENDING)));
		given(paymentGateway.confirm(anyString(), anyString(), any()))
			.willReturn(TossConfirmResult.success("payment-key", "txn-key", "12345678"));
		given(encryptor.encrypt(anyString())).willReturn("encrypted");
		given(paymentRepository.save(any(Payment.class))).willAnswer(inv -> inv.getArgument(0));

		// when
		confirmPaymentService.confirm(command);

		// then
		verify(paymentGateway).confirm("payment-key", ORDER_NUMBER, PG_PART);
	}
}
