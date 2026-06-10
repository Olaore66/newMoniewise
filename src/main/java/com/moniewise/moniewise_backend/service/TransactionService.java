package com.moniewise.moniewise_backend.service;

import com.moniewise.moniewise_backend.dto.TransactionDecisionResponseDto;
import com.moniewise.moniewise_backend.dto.WithdrawalInitiationResponseDto;
import com.moniewise.moniewise_backend.dto.request.WithdrawalRequest;
import com.moniewise.moniewise_backend.dto.response.TransactionDetailResponse;
import com.moniewise.moniewise_backend.dto.response.TransactionListResponse;
import com.moniewise.moniewise_backend.entity.Budget;
import com.moniewise.moniewise_backend.entity.Envelope;
import com.moniewise.moniewise_backend.entity.TransactionDecision;
import com.moniewise.moniewise_backend.entity.TransactionLog;
import com.moniewise.moniewise_backend.entity.TransactionRequest;
import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.entity.Withdrawal;
import com.moniewise.moniewise_backend.enums.TransactionType;
import com.moniewise.moniewise_backend.repository.BudgetRepository;
import com.moniewise.moniewise_backend.repository.EnvelopeRepository;
import com.moniewise.moniewise_backend.repository.TransactionDecisionRepository;
import com.moniewise.moniewise_backend.repository.TransactionLogRepository;
import com.moniewise.moniewise_backend.repository.TransactionRequestRepository;
import com.moniewise.moniewise_backend.repository.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.moniewise.moniewise_backend.enums.TransactionType.BUDGET_ALLOCATION;
import static com.moniewise.moniewise_backend.enums.TransactionType.BUDGET_CREATION_FEE;
import static com.moniewise.moniewise_backend.enums.TransactionType.BUDGET_UNALLOCATED_REFUNDED;
import static com.moniewise.moniewise_backend.enums.TransactionType.DISBURSEMENT_REFUNDED;
import static com.moniewise.moniewise_backend.enums.TransactionType.ENVELOPE_DISBURSEMENT;
import static com.moniewise.moniewise_backend.enums.TransactionType.ENVELOPE_TO_ENVELOPE;
import static com.moniewise.moniewise_backend.enums.TransactionType.ENVELOPE_TO_EXTERNAL;
import static com.moniewise.moniewise_backend.enums.TransactionType.ENVELOPE_TO_USER;
import static com.moniewise.moniewise_backend.enums.TransactionType.STRICT_LOCK_ROLLBACK;
import static com.moniewise.moniewise_backend.enums.TransactionType.USER_TO_ENVELOPE;
import static com.moniewise.moniewise_backend.enums.TransactionType.USER_TO_USER;
import static com.moniewise.moniewise_backend.enums.TransactionType.WALLET_DEDUCTION;
import static com.moniewise.moniewise_backend.enums.TransactionType.WALLET_DEPOSIT;
import static com.moniewise.moniewise_backend.enums.TransactionType.WALLET_TO_BUDGET;
import static com.moniewise.moniewise_backend.enums.TransactionType.WALLET_WITHDRAWAL;
import static com.moniewise.moniewise_backend.enums.TransactionType.WALLET_WITHDRAWAL_FEE;

@Service
@Transactional(readOnly = true)
public class TransactionService {

    private final DecisionEngineService decisionEngineService;
    private final WalletService walletService;
    private final TransactionRequestRepository transactionRequestRepository;
    private final TransactionDecisionRepository transactionDecisionRepository;
    private final UserRepository userRepository;
    private final TransactionLogRepository transactionLogRepo;
    private final EnvelopeRepository envelopeRepo;
    private final BudgetRepository budgetRepo;

    private static final Set<TransactionType> USER_VISIBLE_TYPES = Set.of(
            WALLET_DEPOSIT,
            WALLET_TO_BUDGET,
            BUDGET_ALLOCATION,
            ENVELOPE_TO_ENVELOPE,
            ENVELOPE_TO_EXTERNAL,
            ENVELOPE_TO_USER,
            USER_TO_ENVELOPE,
            BUDGET_CREATION_FEE,
            BUDGET_UNALLOCATED_REFUNDED,
            STRICT_LOCK_ROLLBACK,
            DISBURSEMENT_REFUNDED,
            WALLET_WITHDRAWAL
    );

    private static final Set<TransactionType> USER_VISIBLE_OUTGOING_TYPES = EnumSet.of(
            ENVELOPE_DISBURSEMENT,
            ENVELOPE_TO_EXTERNAL,
            ENVELOPE_TO_ENVELOPE,
            ENVELOPE_TO_USER,
            WALLET_DEDUCTION,
            BUDGET_CREATION_FEE,
            BUDGET_ALLOCATION,
            WALLET_WITHDRAWAL,
            WALLET_WITHDRAWAL_FEE,
            WALLET_TO_BUDGET
    );

    public TransactionService(
            DecisionEngineService decisionEngineService,
            WalletService walletService,
            TransactionRequestRepository transactionRequestRepository,
            TransactionDecisionRepository transactionDecisionRepository,
            UserRepository userRepository,
            TransactionLogRepository transactionLogRepo,
            EnvelopeRepository envelopeRepo,
            BudgetRepository budgetRepo
    ) {
        this.decisionEngineService = decisionEngineService;
        this.walletService = walletService;
        this.transactionRequestRepository = transactionRequestRepository;
        this.transactionDecisionRepository = transactionDecisionRepository;
        this.userRepository = userRepository;
        this.transactionLogRepo = transactionLogRepo;
        this.envelopeRepo = envelopeRepo;
        this.budgetRepo = budgetRepo;
    }

    @Transactional
    public WithdrawalInitiationResponseDto initiateWithdrawal(Long userId, WithdrawalRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        String clientReference = UUID.randomUUID().toString();
        TransactionRequest txRequest = new TransactionRequest();
        txRequest.setUser(user);
        txRequest.setAmount(request.getAmount());
        txRequest.setTransactionType(TransactionRequest.TransactionType.WITHDRAWAL);
        txRequest.setClientReference(clientReference);
        txRequest = transactionRequestRepository.save(txRequest);

        TransactionDecision.Decision decision = decisionEngineService.evaluateRequest(txRequest);

        TransactionDecision txDecision = new TransactionDecision();
        txDecision.setTransactionRequest(txRequest);
        txDecision.setDecision(decision);
        txDecision.setKycCheckPassed(true);
        txDecision.setTierCheckPassed(true);
        txDecision.setWalletCheckPassed(true);
        txDecision.setBudgetCheckPassed(true);
        txDecision.setRequiresConfirmation(false);
        if (decision == TransactionDecision.Decision.BLOCK) {
            txDecision.setReasonCode("BLOCKED");
            txDecision.setReasonMessage("Transaction blocked by decision engine");
        }
        transactionDecisionRepository.save(txDecision);

        if (decision == TransactionDecision.Decision.BLOCK) {
            txRequest.setStatus(TransactionRequest.Status.BLOCKED);
            transactionRequestRepository.save(txRequest);
            return new WithdrawalInitiationResponseDto(
                    false,
                    decision,
                    "BLOCKED",
                    "Transaction blocked",
                    txRequest.getId(),
                    null,
                    request.getAmount(),
                    request.getAmount(),
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    request.getAmount(),
                    BigDecimal.ZERO,
                    BigDecimal.ZERO
            );
        }

        Withdrawal withdrawal = walletService.processWithdrawal(userId, request);
        txRequest.setStatus(TransactionRequest.Status.COMPLETED);
        transactionRequestRepository.save(txRequest);

        BigDecimal bankCharge = withdrawal.getTotalDebit()
                .subtract(withdrawal.getAmount())
                .subtract(withdrawal.getFeeAmount());
        BigDecimal remainingBalance = walletService.getWalletByUserId(userId).getBalance();

        return new WithdrawalInitiationResponseDto(
                true,
                decision,
                null,
                null,
                txRequest.getId(),
                withdrawal.getClientReference(),
                withdrawal.getAmount(),
                withdrawal.getAmount(),
                withdrawal.getFeeAmount(),
                bankCharge,
                withdrawal.getTotalDebit(),
                withdrawal.getRecipientReceives(),
                remainingBalance
        );
    }

    @Transactional
    public void initiateTransfer(Long userId, BigDecimal amount, Long sourceEnvelopeId, String destinationReference) {
        // Placeholder until transfer workflow is implemented.
    }

    public Page<TransactionListResponse> getTransactionsForUser(Long userId, int page, int size) {
        int boundedSize = Math.min(size, 100);
        Pageable pageable = PageRequest.of(page, boundedSize);
        Page<TransactionLog> transactionPage =
                transactionLogRepo.findUserVisibleTransactions(userId, USER_VISIBLE_TYPES, pageable);

        Set<Long> envelopeIds = new HashSet<>();
        transactionPage.getContent().forEach(t -> {
            if (t.getSourceEnvelopeId() != null) {
                envelopeIds.add(t.getSourceEnvelopeId());
            }
            if (t.getTargetEnvelopeId() != null) {
                envelopeIds.add(t.getTargetEnvelopeId());
            }
        });

        Map<Long, String> envelopeNames = new HashMap<>();
        if (!envelopeIds.isEmpty()) {
            envelopeRepo.findAllById(envelopeIds)
                    .forEach(envelope -> envelopeNames.put(envelope.getId(), envelope.getName()));
        }

        return transactionPage.map(t -> mapToListResponse(t, envelopeNames));
    }

    public TransactionDetailResponse getTransactionDetail(Long transactionId, Long userId) {
        TransactionLog log = transactionLogRepo.findById(transactionId)
                .orElseThrow(() -> new RuntimeException("Transaction not found"));

        if (!log.getUserId().equals(userId)) {
            throw new SecurityException("Access denied");
        }

        return mapToDetailResponse(log);
    }

    public Map<String, BigDecimal> getMonthTotalsForUser(Long userId, YearMonth month) {
        LocalDateTime start = month.atDay(1).atStartOfDay();
        LocalDateTime end = month.plusMonths(1).atDay(1).atStartOfDay();

        Set<TransactionType> incomingTypes = EnumSet.copyOf(USER_VISIBLE_TYPES);
        incomingTypes.removeAll(USER_VISIBLE_OUTGOING_TYPES);

        BigDecimal incoming = transactionLogRepo.sumAbsoluteAmountByUserAndDateRangeAndTypes(
                userId,
                start,
                end,
                incomingTypes
        );
        BigDecimal outgoingAbs = transactionLogRepo.sumAbsoluteAmountByUserAndDateRangeAndTypes(
                userId,
                start,
                end,
                USER_VISIBLE_OUTGOING_TYPES
        );
        BigDecimal fees = transactionLogRepo.sumFeesByUserAndDateRangeAndTypes(
                userId,
                start,
                end,
                USER_VISIBLE_TYPES
        );

        Map<String, BigDecimal> totals = new HashMap<>();
        totals.put("incoming", incoming);
        totals.put("outgoingAbs", outgoingAbs);
        totals.put("fees", fees);
        return totals;
    }

    public TransactionDecisionResponseDto getDecision(Long transactionRequestId) {
        TransactionDecision decision = transactionDecisionRepository
                .findByTransactionRequestId(transactionRequestId)
                .orElseThrow(() -> new IllegalArgumentException("Decision not found"));

        return new TransactionDecisionResponseDto(
                decision.getTransactionRequest().getId(),
                decision.getDecision(),
                decision.getReasonCode(),
                decision.getReasonMessage(),
                decision.isRequiresConfirmation()
        );
    }

    private TransactionListResponse mapToListResponse(TransactionLog transaction, Map<Long, String> envelopeNameCache) {
        String sourceName = transaction.getSourceEnvelopeId() != null
                ? envelopeNameCache.getOrDefault(transaction.getSourceEnvelopeId(), "Unknown Envelope")
                : "System";

        String targetName = transaction.getTargetEnvelopeId() != null
                ? envelopeNameCache.getOrDefault(transaction.getTargetEnvelopeId(), "Unknown Envelope")
                : "System";

        boolean outgoing = isOutgoing(transaction.getTransactionType(), transaction.getAmount());
        BigDecimal displayAmount = outgoing ? transaction.getAmount().negate() : transaction.getAmount();
        String direction = outgoing ? "OUT" : "IN";

        String title;
        String subtitle = transaction.getDescription();
        String iconType;

        switch (transaction.getTransactionType()) {
            case ENVELOPE_TO_USER:
                title = "Transfer to " + getCounterpartyName(transaction);
                subtitle = "P2P Transfer";
                iconType = "USER";
                break;
            case USER_TO_ENVELOPE:
            case USER_TO_USER:
                title = "Received from " + getCounterpartyName(transaction);
                subtitle = "P2P Received";
                iconType = "USER";
                break;
            case ENVELOPE_TO_EXTERNAL:
                title = transaction.getDescription() != null
                        ? transaction.getDescription().replace("Transfer to ", "")
                        : "Bank Transfer";
                subtitle = "Bank Transfer";
                iconType = "BANK";
                break;
            case ENVELOPE_TO_ENVELOPE:
                title = "Moved to " + targetName;
                subtitle = "From " + sourceName;
                iconType = "SWAP";
                break;
            case WALLET_DEPOSIT:
                title = "Wallet Funded";
                subtitle = "Deposit";
                iconType = "WALLET";
                break;
            case ENVELOPE_DISBURSEMENT:
                title = "Daily Disbursement";
                subtitle = sourceName;
                iconType = "CASH";
                break;
            default:
                title = formatEnumName(transaction.getTransactionType());
                subtitle = "Transaction";
                iconType = "DEFAULT";
                break;
        }

        String path = "/transactions/" + transaction.getId();

        return new TransactionListResponse(
                transaction.getId(),
                title,
                subtitle,
                displayAmount,
                null,
                iconType,
                direction,
                path,
                transaction.getCreatedAt(),
                transaction.getTransactionType()
        );
    }

    private String formatEnumName(TransactionType type) {
        if (type == null) {
            return "Transaction";
        }
        return type.name().charAt(0) + type.name().substring(1).toLowerCase().replace('_', ' ');
    }

    private String getCounterpartyName(TransactionLog transaction) {
        if (transaction.getCounterpartyUserId() != null) {
            return userRepository.findById(transaction.getCounterpartyUserId())
                    .map(user -> {
                        if (user.getProfileData() != null && user.getProfileData().containsKey("name")) {
                            return (String) user.getProfileData().get("name");
                        }
                        if (user.getEmail() != null) {
                            return user.getEmail().split("@")[0];
                        }
                        return "Wisemonie User";
                    })
                    .orElse("Wisemonie User");
        }

        if (transaction.getDescription() != null) {
            if (transaction.getDescription().contains("Transfer to ")) {
                return transaction.getDescription().replace("Transfer to ", "").trim();
            }
            if (transaction.getDescription().contains("Received from ")) {
                return transaction.getDescription().replace("Received from ", "").trim();
            }
        }

        return "Wisemonie User";
    }

    private TransactionDetailResponse mapToDetailResponse(TransactionLog transaction) {
        String sourceName = getEnvelopeName(transaction.getSourceEnvelopeId());
        String targetName = getEnvelopeName(transaction.getTargetEnvelopeId());
        String budgetName = getBudgetName(transaction.getBudgetId());

        boolean debit = isOutgoing(transaction.getTransactionType(), transaction.getAmount());
        String direction = debit ? "DEBIT" : "CREDIT";

        BigDecimal absoluteAmount = transaction.getAmount().abs();
        BigDecimal fee = transaction.getFee() != null ? transaction.getFee() : BigDecimal.ZERO;
        BigDecimal netAmount = debit ? absoluteAmount.add(fee) : absoluteAmount.subtract(fee);

        String sender;
        String recipient;

        switch (transaction.getTransactionType()) {
            case BUDGET_ALLOCATION:
                sender = "Main Wallet";
                recipient = budgetName != null ? "Budget: " + budgetName : "Budget Envelopes";
                break;
            case BUDGET_CREATION_FEE:
            case WALLET_WITHDRAWAL_FEE:
                sender = "Main Wallet";
                recipient = "MonieWise Fee";
                break;
            case BUDGET_UNALLOCATED_REFUNDED:
                sender = "Unallocated Funds";
                recipient = "Main Wallet";
                break;
            case WALLET_DEPOSIT:
                sender = "External Transfer / Card";
                recipient = "Main Wallet";
                break;
            case WALLET_WITHDRAWAL:
            case ENVELOPE_TO_EXTERNAL:
                sender = transaction.getTransactionType() == WALLET_WITHDRAWAL ? "Main Wallet" : sourceName;
                // Prefer the resolved account name (V19+); fall back to legacy externalAccountId.
                String extAccName = transaction.getExternalAccountName();
                String extAccId   = transaction.getExternalAccountId();
                recipient = (extAccName != null && !extAccName.isBlank()) ? extAccName
                          : (extAccId  != null && !extAccId.isBlank())   ? extAccId
                          : "External Account";
                break;
            case ENVELOPE_TO_ENVELOPE:
                sender = sourceName;
                recipient = targetName;
                break;
            case ENVELOPE_TO_USER:
                sender = sourceName;
                recipient = getCounterpartyName(transaction);
                break;
            case USER_TO_ENVELOPE:
                sender = getCounterpartyName(transaction);
                recipient = targetName;
                break;
            default:
                sender = "MonieWise Account";
                recipient = "MonieWise Account";
                break;
        }

        String title = switch (transaction.getTransactionType()) {
            case WALLET_DEPOSIT -> "Wallet funded";
            case ENVELOPE_TO_ENVELOPE -> "From %s to %s".formatted(sourceName, targetName);
            case ENVELOPE_TO_EXTERNAL -> "Sent to bank";
            case ENVELOPE_TO_USER -> "Sent to " + getCounterpartyName(transaction);
            case USER_TO_ENVELOPE -> "Received from " + getCounterpartyName(transaction);
            case BUDGET_CREATION_FEE -> "Budget creation fee";
            case WALLET_WITHDRAWAL_FEE -> "Withdrawal fee";
            case BUDGET_ALLOCATION -> "Allocated to budget";
            case BUDGET_UNALLOCATED_REFUNDED -> "Refunded to wallet";
            default -> formatEnumName(transaction.getTransactionType());
        };

        String reference = transaction.getReference() != null ? transaction.getReference() : "N/A";
        String status = transaction.getStatus() != null ? transaction.getStatus().name() : "COMPLETED";

        // For external bank transfers, surface the bank name as targetEnvelopeName so the
        // hero card's "To" field shows the bank rather than "System".
        boolean isExternalTransfer =
                transaction.getTransactionType() == ENVELOPE_TO_EXTERNAL
                || transaction.getTransactionType() == WALLET_WITHDRAWAL;
        String effectiveTargetName = (isExternalTransfer
                && transaction.getExternalBankName() != null
                && !transaction.getExternalBankName().isBlank())
                ? transaction.getExternalBankName()
                : targetName;

        // Resolve account number: prefer explicit externalAccountNumber (V19+),
        // fall back to the legacy externalAccountId column.
        String resolvedAccountNumber = (transaction.getExternalAccountNumber() != null
                && !transaction.getExternalAccountNumber().isBlank())
                ? transaction.getExternalAccountNumber()
                : transaction.getExternalAccountId();

        return new TransactionDetailResponse(
                transaction.getId(),
                reference,
                status,
                direction,
                title,
                transaction.getDescription(),
                absoluteAmount,
                fee,
                netAmount,
                sender,
                recipient,
                transaction.getTransactionType(),
                transaction.getCreatedAt(),
                transaction.getBudgetId(),
                budgetName,
                sourceName,
                transaction.getSourceEnvelopeId(),
                effectiveTargetName,
                transaction.getTargetEnvelopeId(),
                transaction.getExternalAccountId(),
                transaction.getExternalBankName(),
                resolvedAccountNumber
        );
    }

    private String getEnvelopeName(Long envelopeId) {
        if (envelopeId == null) {
            return "System";
        }
        return envelopeRepo.findById(envelopeId)
                .map(Envelope::getName)
                .orElse("Unknown Envelope");
    }

    private String getBudgetName(Long budgetId) {
        if (budgetId == null) {
            return "No Budget";
        }
        return budgetRepo.findById(budgetId)
                .map(Budget::getName)
                .orElse("Unknown Budget");
    }

    private boolean isOutgoing(TransactionType type, BigDecimal amount) {
        if (amount != null && amount.compareTo(BigDecimal.ZERO) < 0) {
            return true;
        }
        return switch (type) {
            case ENVELOPE_DISBURSEMENT,
                    ENVELOPE_TO_EXTERNAL,
                    ENVELOPE_TO_ENVELOPE,
                    ENVELOPE_TO_USER,
                    WALLET_DEDUCTION,
                    WALLET_WITHDRAWAL_FEE,
                    BUDGET_CREATION_FEE,
                    BUDGET_ALLOCATION,
                    WALLET_WITHDRAWAL -> true;
            default -> false;
        };
    }
}
