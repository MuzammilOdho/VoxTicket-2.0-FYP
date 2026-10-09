package com.voxticket.conversation;

import com.voxticket.procedure.ProcedureOutcome;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Pass 2C (Task 3): deterministic, language-aware presentation for the direct
 * runtime path (OTP / confirmation turns that never reach the LLM).
 *
 * <p>The renderer is keyed ONLY on {@link ProcedureOutcome#code()} and safe
 * structured metadata. It never parses, translates, or otherwise reads
 * {@code ProcedureOutcome.message()}, never inspects exception text, never
 * calls the LLM or a provider, never touches the database, and never makes a
 * business decision.
 *
 * <p>Plain text, one or two sentences, no markdown, no emojis. Stable
 * identifiers (order/return/claim numbers) are reproduced unchanged.
 */
@Component
public class DirectProcedureResponseRenderer {

    private static final Logger log = LoggerFactory.getLogger(DirectProcedureResponseRenderer.class);

    /**
     * Renders a deterministic customer-facing response for a direct-path
     * procedure outcome. Never throws for an unknown code - a localized safe
     * fallback is returned instead.
     */
    public String render(ConversationLanguage language, ProcedureOutcome outcome) {
        ConversationLanguage lang = language == null ? ConversationLanguage.ENGLISH : language;
        if (outcome == null || outcome.code() == null) {
            log.warn("event=direct_response_unknown_code code=null language={}", lang);
            return fallback(lang);
        }
        Map<String, String> metadata = outcome.metadata() == null ? Map.of() : outcome.metadata();
        String rendered = switch (outcome.code()) {
            case "VERIFICATION_REQUIRED" -> verificationRequired(lang, metadata);
            case "VERIFICATION_FAILED" -> verificationFailed(lang, metadata);
            case "OTP_AMBIGUOUS" -> otpAmbiguous(lang);
            case "VERIFICATION_RATE_LIMITED" -> verificationRateLimited(lang, metadata);
            case "NO_PENDING_VERIFICATION" -> noPendingVerification(lang);
            case "CANCELLED" -> cancelled(lang, metadata);
            case "RETURN_STARTED" -> returnStarted(lang, metadata);
            case "CLAIM_FILED" -> claimFiled(lang, metadata);
            case "DECLINED" -> declined(lang);
            case "NO_PENDING_CONFIRMATION" -> noPendingConfirmation(lang);
            case "EXPIRED" -> expired(lang);
            case "IDENTITY_NOT_VERIFIED" -> identityNotVerified(lang);
            case "EXECUTION_FAILED" -> executionFailed(lang);
            // Pass 2D-B cleanup: every outcome promoteDeferredIntent can
            // legitimately return has deterministic customer-facing handling.
            // ALREADY_PENDING / ALREADY_DEFERRED / PROCEDURE_DEFERRED /
            // PENDING_REQUEST_LIMIT_REACHED / DEFERRED_REQUEST_PENDING are
            // unreachable from promotion by construction (the active slot is
            // empty and the intent is the one being promoted), so they keep
            // the safe fallback rather than a fabricated rendering.
            case "CONFIRMATION_REQUIRED" -> confirmationRequired(lang, metadata);
            case "NOT_ELIGIBLE" -> notEligible(lang, metadata);
            case "NOT_FOUND_FOR_ACCOUNT" -> notFoundForAccount(lang, metadata);
            case "ITEM_REQUIRED" -> itemRequired(lang, metadata);
            case "REASON_REQUIRED" -> reasonRequired(lang, metadata);
            case "PROBLEM_REQUIRED" -> problemRequired(lang, metadata);
            case "QUANTITY_REQUIRED" -> quantityRequired(lang, metadata);
            case "PROMOTION_FAILED" -> promotionFailed(lang);
            default -> {
                log.warn("event=direct_response_unknown_code code={} language={}", outcome.code(), lang);
                yield fallback(lang);
            }
        };
        return rendered;
    }

    // ---- verification ----

    private String verificationRequired(ConversationLanguage lang, Map<String, String> metadata) {
        String masked = metadata.get("maskedDestination");
        return switch (lang) {
            case URDU -> masked != null
                    ? "چھ ہندسوں کا تصدیقی کوڈ " + masked + " پر بھیجا گیا ہے۔ براہ کرم وہ کوڈ یہاں لکھیں۔"
                    : "آپ کو چھ ہندسوں کا تصدیقی کوڈ بھیجا گیا ہے۔ براہ کرم وہ کوڈ یہاں لکھیں۔";
            case ROMAN_URDU -> masked != null
                    ? "Aap ko 6 hisson wala tasdeeqi code " + masked + " par bheja gaya hai. Barah-e-karam woh code yahan likhein."
                    : "Aap ko 6 hisson wala tasdeeqi code bheja gaya hai. Barah-e-karam woh code yahan likhein.";
            case CODE_SWITCH -> masked != null
                    ? "Aap ko 6-digit verification code " + masked + " par bheja gaya hai. Please woh code yahan enter karein."
                    : "Aap ko 6-digit verification code bheja gaya hai. Please woh code yahan enter karein.";
            default -> masked != null
                    ? "I've sent a 6-digit verification code to " + masked + ". Could you read that back to me once you receive it?"
                    : "I've sent you a 6-digit verification code. Could you read that back to me once you receive it?";
        };
    }

    private String otpAmbiguous(ConversationLanguage lang) {
        return switch (lang) {
            case URDU -> "آپ کے پیغام میں ایک سے زیادہ کوڈ نظر آ رہے ہیں۔ براہ کرم صرف 6 ہندسوں والا تصدیقی کوڈ الگ سے بھیجیں۔";
            case ROMAN_URDU -> "Aap ke message mein ek se zyada codes nazar aa rahe hain. Barah-e-karam sirf 6 hisson wala tasdeeqi code alag se bhejein.";
            case CODE_SWITCH -> "Aap ke message mein ek se zyada codes hain. Please sirf 6-digit verification code alag se send karein.";
            default -> "I see more than one code in your message. Please send just the 6-digit verification code on its own.";
        };
    }

    private String verificationFailed(ConversationLanguage lang, Map<String, String> metadata) {
        return switch (metadata.getOrDefault("verificationReason", "")) {
            case "WRONG_CODE" -> switch (lang) {
                case URDU -> "یہ کوڈ درست نہیں ہے۔ براہ کرم دوبارہ چیک کر کے کوشش کریں۔";
                case ROMAN_URDU -> "Yeh code match nahi hua. Dobara check karke try karein.";
                case CODE_SWITCH -> "Code match nahi hua. Please check karke dobara try karein.";
                default -> "That code didn't match. Please double check and try again.";
            };
            case "EXPIRED" -> switch (lang) {
                case URDU -> "یہ کوڈ میعاد سے باہر ہو گیا ہے۔ براہ کرم نیا کوڈ منگوائیں۔";
                case ROMAN_URDU -> "Yeh code expire ho gaya hai. Barah-e-karam naya code mangwayein.";
                case CODE_SWITCH -> "Yeh code expire ho gaya hai. Please naya code request karein.";
                default -> "That code has expired. Please request a new one.";
            };
            case "NO_LONGER_VALID" -> switch (lang) {
                case URDU -> "یہ کوڈ اب قابلِ استعمال نہیں ہے۔ براہ کرم نیا کوڈ منگوائیں۔";
                case ROMAN_URDU -> "Yeh code ab qabil-e-istemal nahi raha. Barah-e-karam naya code mangwayein.";
                case CODE_SWITCH -> "Yeh code ab valid nahi hai. Please naya code request karein.";
                default -> "That code is no longer valid. Please request a new one.";
            };
            case "BINDING_MISMATCH" -> switch (lang) {
                case URDU -> "یہ کوڈ اس عمل کے لیے استعمال نہیں ہو سکتا۔ براہ کرم تصدیق کا نیا کوڈ منگوائیں۔";
                case ROMAN_URDU -> "Yeh code is amal ke liye istemal nahi ho sakta. Barah-e-karam tasdeeq ka naya code mangwayein.";
                case CODE_SWITCH -> "Yeh code is action ke liye use nahi ho sakta. Please naya verification code request karein.";
                default -> "That code can't be used for this action. Please request a new verification code.";
            };
            case "MAX_ATTEMPTS_EXCEEDED" -> switch (lang) {
                case URDU -> "بہت سی غلط کوششیں ہو چکی ہیں۔ یہ کوڈ اب استعمال نہیں ہو سکتا۔ براہ کرم نیا کوڈ منگوائیں۔";
                case ROMAN_URDU -> "Bohat si ghalat koshishen ho chuki hain. Yeh code ab istemal nahi ho sakta. Barah-e-karam naya code mangwayein.";
                case CODE_SWITCH -> "Bahut si ghalat attempts ho gayi hain. Yeh code ab use nahi ho sakta. Please naya code request karein.";
                default -> "Too many incorrect attempts. That code can't be used anymore - please request a new one.";
            };
            default -> noPendingVerification(lang);
        };
    }

    private String verificationRateLimited(ConversationLanguage lang, Map<String, String> metadata) {
        if ("RESEND_COOLDOWN".equals(metadata.get("verificationIssue"))) {
            return switch (lang) {
                case URDU -> "کوڈ ابھی بھیجا گیا ہے۔ براہ کرم دوبارہ مانگنے سے پہلے تھوڑی دیر انتظار کریں۔";
                case ROMAN_URDU -> "Code abhi bheja gaya hai. Barah-e-karam dobara mangne se pehle thodi der intezar karein.";
                case CODE_SWITCH -> "Code abhi bheja gaya hai. Please dobara request karne se pehle thodi der wait karein.";
                default -> "A code was just sent. Please wait a moment before requesting another.";
            };
        }
        return switch (lang) {
            case URDU -> "بہت سی کوششوں کی وجہ سے تصدیق عارضی طور پر دستیاب نہیں ہے۔ براہ کرم کچھ دیر بعد دوبارہ کوشش کریں یا انسانی نمائندے سے رابطہ کریں۔";
            case ROMAN_URDU -> "Bohat si koshishon ki wajah se tasdeeq arzi tor par dastyab nahi hai. Barah-e-karam kuch der baad dobara koshish karein ya insani numainde se rabta karein.";
            case CODE_SWITCH -> "Bohat si attempts ki wajah se verification temporarily unavailable hai. Please thodi der baad try karein ya human agent se baat karein.";
            default -> "Verification is temporarily unavailable because of too many attempts. Please try again later or ask for a human agent.";
        };
    }

    private String noPendingVerification(ConversationLanguage lang) {
        return switch (lang) {
            case URDU -> "اس وقت کوئی تصدیقی کوڈ زیرِ انتظار نہیں ہے۔";
            case ROMAN_URDU -> "Is waqt koi tasdeeqi code pending nahi hai.";
            case CODE_SWITCH -> "Is waqt koi verification code pending nahi hai.";
            default -> "There's no verification code pending right now.";
        };
    }

    // ---- execution outcomes ----

    private String cancelled(ConversationLanguage lang, Map<String, String> metadata) {
        String orderRef = metadata.getOrDefault("orderReference", "").strip();
        String consequence = metadata.getOrDefault("paymentConsequence", "");
        String cancelledBase = switch (lang) {
            case URDU -> "آرڈر " + orderRef + " منسوخ کر دیا گیا ہے۔";
            case ROMAN_URDU -> "Order " + orderRef + " cancel kar diya gaya hai.";
            case CODE_SWITCH -> "Order " + orderRef + " cancel ho gaya hai.";
            default -> "Order " + orderRef + " has been cancelled.";
        };
        String suffix = switch (consequence) {
            case "NO_REFUND_REQUIRED" -> switch (lang) {
                case URDU -> " کوئی ادائیگی وصول نہیں ہوئی تھی، اس لیے رقم واپس کرنے کی ضرورت نہیں ہے۔";
                case ROMAN_URDU -> " Koi adayegi wasool nahi hui thi, is liye refund ki zaroorat nahi hai.";
                case CODE_SWITCH -> " Koi payment collect nahi hui thi, is liye koi refund nahi ban raha.";
                default -> " No payment was collected, so there's nothing to refund.";
            };
            case "VOID_AUTHORIZATION" -> switch (lang) {
                case URDU -> " ادائیگی صرف عارضی طور پر محفوظ تھی اور وصول نہیں ہوئی، اس لیے رقم واپس کرنے کی ضرورت نہیں ہے۔";
                case ROMAN_URDU -> " Adayegi sirf arzi tor par mehfooz thi aur wasool nahi hui, is liye refund ki zaroorat nahi hai.";
                case CODE_SWITCH -> " Payment sirf authorized thi, capture nahi hui, is liye koi refund nahi ban raha.";
                default -> " The payment was only authorized and never captured, so there's nothing to refund.";
            };
            case "REFUND_REQUIRED" -> switch (lang) {
                case URDU -> " مکمل رقم کی واپسی جاری کی جائے گی۔";
                case ROMAN_URDU -> " Mukammal raqam ka refund jari kiya jayega.";
                case CODE_SWITCH -> " Full amount ka refund issue ho jayega.";
                default -> " A refund will be issued for the full amount.";
            };
            case "MANUAL_REVIEW_REQUIRED" -> switch (lang) {
                case URDU -> " اس آرڈر کی ادائیگی کو رقم کی واپسی کے فیصلے سے پہلے دستی جائزے کی ضرورت ہے۔";
                case ROMAN_URDU -> " Is order ki adayegi ko refund ke faisle se pehle manual review ki zaroorat hai.";
                case CODE_SWITCH -> " Is order ki payment ko refund decision se pehle manual review ki zaroorat hai.";
                default -> " The payment on this order needs manual review before a refund decision can be made.";
            };
            default -> "";
        };
        return cancelledBase + suffix;
    }

    private String returnStarted(ConversationLanguage lang, Map<String, String> metadata) {
        String orderRef = metadata.getOrDefault("orderReference", "");
        String ret = metadata.getOrDefault("returnNumber", "");
        return switch (lang) {
            case URDU -> (ret.isBlank() ? "واپسی" : "واپسی " + ret) + " آرڈر " + orderRef + " کے لیے شروع کر دی گئی ہے۔ یہ اب درخواست کی حالت میں ہے اور منظوری کا انتظار ہے۔";
            case ROMAN_URDU -> (ret.isBlank() ? "Return" : "Return " + ret) + " order " + orderRef + " ke liye shuru kar di gayi hai. Yeh ab requested hai aur approval ka intezar hai.";
            case CODE_SWITCH -> (ret.isBlank() ? "Return" : "Return " + ret) + " order " + orderRef + " ke liye start ho gayi hai. Yeh ab requested hai aur approval pending hai.";
            default -> (ret.isBlank() ? "A return" : "Return " + ret) + " has been started for order " + orderRef + ". It is now requested and awaiting approval.";
        };
    }

    private String claimFiled(ConversationLanguage lang, Map<String, String> metadata) {
        String orderRef = metadata.getOrDefault("orderReference", "");
        String clm = metadata.getOrDefault("claimNumber", "");
        return switch (lang) {
            case URDU -> "آرڈر " + orderRef + " کے لیے دعویٰ " + clm + " درج کر دیا گیا ہے اور ہماری ٹیم کے جائزے کا منتظر ہے۔";
            case ROMAN_URDU -> "Order " + orderRef + " ke liye claim " + clm + " darj kar diya gaya hai aur hamari team ke jaizay ka muntazir hai.";
            case CODE_SWITCH -> "Order " + orderRef + " ke liye claim " + clm + " file ho gaya hai aur hamari team ke review ka wait kar raha hai.";
            default -> "Claim " + clm + " has been filed for order " + orderRef + " and is awaiting review by our team.";
        };
    }

    private String declined(ConversationLanguage lang) {
        return switch (lang) {
            case URDU -> "کوئی مسئلہ نہیں۔ میں اس پر آگے نہیں بڑھوں گا۔";
            case ROMAN_URDU -> "Koi masla nahi. Main is par aage nahi barhunga.";
            case CODE_SWITCH -> "No problem. Main is par aage nahi badhunga.";
            default -> "No problem - I won't go ahead with that.";
        };
    }

    // ---- confirmation / failure plumbing ----

    private String noPendingConfirmation(ConversationLanguage lang) {
        return switch (lang) {
            case URDU -> "اس وقت تصدیق کے لیے کچھ زیرِ انتظار نہیں ہے۔";
            case ROMAN_URDU -> "Is waqt tasdeeq ke liye kuch pending nahi hai.";
            case CODE_SWITCH -> "Is waqt confirmation ke liye kuch pending nahi hai.";
            default -> "There's nothing waiting for confirmation right now.";
        };
    }

    private String expired(ConversationLanguage lang) {
        return switch (lang) {
            case URDU -> "یہ درخواست میعاد سے باہر ہو گئی ہے۔ اگر آپ چاہیں تو ہم نئے سرے سے شروع کر سکتے ہیں۔";
            case ROMAN_URDU -> "Yeh darkhwast expire ho gayi hai. Agar aap chahen to hum naye sire se shuru kar sakte hain.";
            case CODE_SWITCH -> "Yeh request expire ho gayi hai. Agar aap chahein to hum dobara start kar sakte hain.";
            default -> "That request has expired. Let's start again if you'd still like to go ahead.";
        };
    }

    private String identityNotVerified(ConversationLanguage lang) {
        return switch (lang) {
            case URDU -> "آگے بڑھنے سے پہلے مجھے آپ کی شناخت کی تصدیق کرنی ہوگی۔";
            case ROMAN_URDU -> "Aage barhne se pehle mujhe aap ki shanakht ki tasdeeq karni hogi.";
            case CODE_SWITCH -> "Aage badhne se pehle mujhe aapki identity verify karni hogi.";
            default -> "I'll need to verify who I'm speaking with before I can go ahead.";
        };
    }

    private String executionFailed(ConversationLanguage lang) {
        return switch (lang) {
            case URDU -> "اس عمل میں کوئی مسئلہ پیش آیا ہے۔ براہ کرم دوبارہ کوشش کریں یا انسانی نمائندے سے رابطہ کریں۔";
            case ROMAN_URDU -> "Is amal mein koi masla pesh aaya hai. Barah-e-karam dobara koshish karein ya insani numainde se rabta karein.";
            case CODE_SWITCH -> "Is process mein kuch issue aaya hai. Please dobara try karein ya human agent se baat karein.";
            default -> "Something went wrong while processing that. Please try again, or ask for a human agent.";
        };
    }

    // ---- promotion outcomes (Pass 2D-B cleanup) ----
    //
    // Every outcome ProcedureCoordinator.promoteDeferredIntent can
    // legitimately return is rendered deterministically here from the
    // outcome code and safe structured metadata only. Rendered from the
    // same templates in all four language modes; nothing is parsed from
    // ProcedureOutcome.message() and no internal identifiers ever surface.

    /**
     * A promoted claim reached CONFIRMATION_REQUIRED: the newly promoted
     * claim is ready and needs explicit confirmation - never a generic
     * failure. No refund or replacement is promised; claim resolution stays
     * manual review unless authoritative state says otherwise.
     */
    private String confirmationRequired(ConversationLanguage lang, Map<String, String> metadata) {
        String orderRef = metadata.getOrDefault("orderReference", "").strip();
        String itemName = metadata.getOrDefault("itemName", "").strip();
        String reasonPhrase = claimReasonPhrase(lang, metadata.getOrDefault("claimReason", ""));
        String described = metadata.getOrDefault("problemDescription", "").strip();
        String describedPart = described.isEmpty() ? "" : switch (lang) {
            case URDU -> " آپ نے بتایا: \"" + described + "\"۔";
            case ROMAN_URDU -> " Aap ne bataya: \"" + described + "\".";
            case CODE_SWITCH -> " Aap ne bataya: \"" + described + "\".";
            default -> " You described it as: \"" + described + "\".";
        };
        return switch (lang) {
            case URDU -> "آرڈر " + orderRef + " پر " + itemName + " کے لیے آپ کا دعویٰ" + reasonPhrase
                    + " دائر کرنے کے لیے تیار ہے۔" + describedPart
                    + " براہ کرم دائر کرنے کے لیے واضح طور پر 'ہاں' لکھیں، یا چھوڑنے کے لیے 'نہیں'۔";
            case ROMAN_URDU -> "Order " + orderRef + " par " + itemName + " ke liye aap ka claim" + reasonPhrase
                    + " file karne ke liye tayyar hai." + describedPart
                    + " File karne ke liye wazeh 'haan' likhein, ya chhorne ke liye 'nahi'.";
            case CODE_SWITCH -> "Order " + orderRef + " par " + itemName + " ke liye aapka claim" + reasonPhrase
                    + " file karne ke liye ready hai." + describedPart
                    + " File karne ke liye clear 'yes' reply karein, ya drop karne ke liye 'no'.";
            default -> "Your claim for the " + itemName + " on order " + orderRef + reasonPhrase
                    + " is ready to file." + describedPart
                    + " Please reply with a clear yes to file it, or no to drop it.";
        };
    }

    private String claimReasonPhrase(ConversationLanguage lang, String claimReason) {
        return switch (claimReason) {
            case "DAMAGED" -> switch (lang) {
                case URDU -> " (خراب)";
                case ROMAN_URDU -> " (kharab)";
                default -> " (damaged)";
            };
            case "DEFECTIVE" -> switch (lang) {
                case URDU -> " (ناقص)";
                case ROMAN_URDU -> " (naqis)";
                default -> " (defective)";
            };
            case "WRONG_ITEM" -> switch (lang) {
                case URDU -> " (غلط چیز موصول ہوئی)";
                case ROMAN_URDU -> " (ghalat cheez mili)";
                default -> " (wrong item received)";
            };
            case "MISSING_ITEM" -> switch (lang) {
                case URDU -> " (چیز غائب)";
                case ROMAN_URDU -> " (cheez ghaib)";
                default -> " (missing item)";
            };
            case "OTHER" -> switch (lang) {
                case URDU -> " (دیگر مسئلہ)";
                case ROMAN_URDU -> " (doosra masla)";
                default -> " (other issue)";
            };
            default -> "";
        };
    }

    /**
     * Deterministic denial for a promotion that found the deferred action no
     * longer eligible. The denial reason enum is translated, never the
     * coordinator's English prose.
     */
    private String notEligible(ConversationLanguage lang, Map<String, String> metadata) {
        String orderRef = metadata.getOrDefault("orderReference", "").strip();
        String itemName = metadata.getOrDefault("itemName", "").strip();
        String denialReason = metadata.getOrDefault("denialReason", "");
        String reasonSentence = denialReasonSentence(lang, denialReason);
        String reasonPart = reasonSentence.isEmpty() ? "" : " " + reasonSentence;
        if (itemName.isEmpty()) {
            // Cancellation denial.
            return switch (lang) {
                case URDU -> "آرڈر " + orderRef + " منسوخ نہیں ہو سکتا۔" + reasonPart;
                case ROMAN_URDU -> "Order " + orderRef + " cancel nahi ho sakta." + reasonPart;
                case CODE_SWITCH -> "Order " + orderRef + " cancel nahi ho sakta." + reasonPart;
                default -> "Order " + orderRef + " can't be cancelled." + reasonPart;
            };
        }
        if (denialReason.isEmpty()) {
            // Claim denial: the only current claim NOT_ELIGIBLE is a
            // cancelled order (see ProcedureCoordinator.startClaim).
            return switch (lang) {
                case URDU -> "آرڈر " + orderRef + " پر " + itemName + " کے لیے دعویٰ دائر نہیں ہو سکتا - آرڈر منسوخ ہے۔";
                case ROMAN_URDU -> "Order " + orderRef + " par " + itemName + " ke liye claim file nahi ho sakta - order cancel hai.";
                case CODE_SWITCH -> "Order " + orderRef + " par " + itemName + " ke liye claim file nahi ho sakta - order cancelled hai.";
                default -> "A claim can't be filed for the " + itemName + " on order " + orderRef + " - the order was cancelled.";
            };
        }
        // Return denial.
        return switch (lang) {
            case URDU -> "آرڈر " + orderRef + " پر " + itemName + " واپس نہیں ہو سکتا۔" + reasonPart;
            case ROMAN_URDU -> "Order " + orderRef + " par " + itemName + " return nahi ho sakta." + reasonPart;
            case CODE_SWITCH -> "Order " + orderRef + " par " + itemName + " return nahi ho sakta." + reasonPart;
            default -> "The " + itemName + " on order " + orderRef + " can't be returned." + reasonPart;
        };
    }

    private String denialReasonSentence(ConversationLanguage lang, String denialReason) {
        return switch (denialReason) {
            case "ALREADY_CANCELLED" -> switch (lang) {
                case URDU -> "یہ پہلے ہی منسوخ ہے۔";
                case ROMAN_URDU -> "Yeh pehle hi cancel hai.";
                case CODE_SWITCH -> "Yeh already cancelled hai.";
                default -> "It's already cancelled.";
            };
            case "ORDER_ALREADY_COMPLETED" -> switch (lang) {
                case URDU -> "یہ پہلے ہی مکمل ہو چکا ہے۔";
                case ROMAN_URDU -> "Yeh pehle hi complete ho chuka hai.";
                case CODE_SWITCH -> "Yeh already complete ho chuka hai.";
                default -> "It's already completed.";
            };
            case "ORDER_FULFILLED" -> switch (lang) {
                // Phase 1C: this denial fires for PARTIALLY_FULFILLED as well
                // as FULFILLED, so "delivered" would be false. "Shipped /
                // dispatched" is true in every case the code covers.
                case URDU -> "یہ پہلے ہی بھیج دیا گیا ہے۔";
                case ROMAN_URDU -> "Yeh pehle hi bhej diya gaya hai.";
                case CODE_SWITCH -> "Yeh already dispatch ho chuka hai.";
                default -> "It has already been fulfilled.";
            };
            case "PAYMENT_STATE_INCOMPATIBLE" -> switch (lang) {
                case URDU -> "اس کی ادائیگی کی حالت منسوخی کی اجازت نہیں دیتی۔";
                case ROMAN_URDU -> "Iski payment state cancellation ki ijazat nahi deti.";
                case CODE_SWITCH -> "Iski payment state cancellation allow nahi karti.";
                default -> "Its payment state doesn't allow cancellation.";
            };
            case "ITEM_NOT_DELIVERED" -> switch (lang) {
                case URDU -> "یہ ابھی ڈیلیور نہیں ہوا ہے۔";
                case ROMAN_URDU -> "Yeh abhi deliver nahi hua hai.";
                case CODE_SWITCH -> "Yeh abhi deliver nahi hua hai.";
                default -> "It hasn't been delivered yet.";
            };
            case "RETURN_WINDOW_EXPIRED" -> switch (lang) {
                case URDU -> "واپسی کی مدت ختم ہو چکی ہے۔";
                case ROMAN_URDU -> "Return ki muddat khatam ho chuki hai.";
                case CODE_SWITCH -> "Return window expire ho chuki hai.";
                default -> "The return window has expired.";
            };
            case "ITEM_FINAL_SALE" -> switch (lang) {
                case URDU -> "یہ فائنل سیل پر تھا۔";
                case ROMAN_URDU -> "Yeh final sale par tha.";
                case CODE_SWITCH -> "Yeh final sale par tha.";
                default -> "It was sold as a final sale.";
            };
            case "ITEM_NOT_RETURNABLE" -> switch (lang) {
                case URDU -> "یہ واپس نہیں ہو سکتا۔";
                case ROMAN_URDU -> "Yeh return nahi ho sakta.";
                case CODE_SWITCH -> "Yeh returnable nahi hai.";
                default -> "It isn't returnable.";
            };
            case "NO_REMAINING_RETURNABLE_QUANTITY" -> switch (lang) {
                case URDU -> "اس کی کوئی قابلِ واپسی مقدار باقی نہیں ہے۔";
                case ROMAN_URDU -> "Iski koi returnable quantity baqi nahi hai.";
                case CODE_SWITCH -> "Iski koi returnable quantity baqi nahi hai.";
                default -> "There's no returnable quantity left.";
            };
            default -> "";
        };
    }

    private String notFoundForAccount(ConversationLanguage lang, Map<String, String> metadata) {
        String orderRef = metadata.getOrDefault("orderReference", "").strip();
        return switch (lang) {
            case URDU -> "آپ کے اکاؤنٹ پر آرڈر " + orderRef + " نہیں ملا۔";
            case ROMAN_URDU -> "Aap ke account par order " + orderRef + " nahi mila.";
            case CODE_SWITCH -> "Aapke account par order " + orderRef + " nahi mila.";
            default -> "I couldn't find order " + orderRef + " on your account.";
        };
    }

    private String itemRequired(ConversationLanguage lang, Map<String, String> metadata) {
        String orderRef = metadata.getOrDefault("orderReference", "").strip();
        StringBuilder candidates = new StringBuilder();
        for (int i = 1; i <= 5; i++) {
            String candidate = metadata.get("candidateItem." + i);
            if (candidate == null || candidate.isBlank()) {
                break;
            }
            if (candidates.length() > 0) {
                candidates.append(", ");
            }
            candidates.append(candidate);
        }
        if (candidates.length() > 0) {
            return switch (lang) {
                case URDU -> "آرڈر " + orderRef + " پر چند چیزیں مل سکتی ہیں: " + candidates + "۔ آپ کا مطلب کون سی ہے؟";
                case ROMAN_URDU -> "Order " + orderRef + " par chand cheezein match ho sakti hain: " + candidates + ". Aap ka matlab kaun si hai?";
                case CODE_SWITCH -> "Order " + orderRef + " par kuch items match ho sakte hain: " + candidates + ". Aap ka matlab kaun sa hai?";
                default -> "This order has a few items that could match: " + candidates + ". Which one did you mean?";
            };
        }
        return switch (lang) {
            case URDU -> "آرڈر " + orderRef + " پر اس چیز کی شناخت نہیں ہو سکی۔ براہ کرم بتائیں آپ کا مطلب کون سی چیز ہے؟";
            case ROMAN_URDU -> "Order " + orderRef + " par is cheez ki shanakht nahi ho saki. Barah-e-karam batayein aap ka matlab kaun si cheez hai?";
            case CODE_SWITCH -> "Order " + orderRef + " par is item ko match nahi kar saka. Please batayein aap ka matlab kaun sa item hai?";
            default -> "I couldn't match that to an item on order " + orderRef + ". Could you describe which item you mean?";
        };
    }

    private String reasonRequired(ConversationLanguage lang, Map<String, String> metadata) {
        String itemName = metadata.getOrDefault("itemName", "").strip();
        return switch (lang) {
            case URDU -> "براہ کرم بتائیں آپ " + itemName + " کیوں واپس کرنا چاہتے ہیں - مثلاً غلط سائز، خراب، یا ارادہ بدل گیا؟";
            case ROMAN_URDU -> "Barah-e-karam batayein aap " + itemName + " kyun wapas karna chahte hain - masalan ghalat size, kharab, ya irada badal gaya?";
            case CODE_SWITCH -> "Please batayein aap " + itemName + " kyun return karna chahte hain - for example wrong size, damaged, ya mind change ho gaya?";
            default -> "Could you tell me why you'd like to return the " + itemName + " - for example wrong size, damaged, or you changed your mind?";
        };
    }

    private String problemRequired(ConversationLanguage lang, Map<String, String> metadata) {
        String itemName = metadata.getOrDefault("itemName", "").strip();
        return switch (lang) {
            case URDU -> "براہ کرم بتائیں " + itemName + " کے ساتھ کیا ہوا - مثلاً کیا وہ خراب تھی، ناقص تھی، غلط چیز تھی، یا غائب ہے؟";
            case ROMAN_URDU -> "Barah-e-karam batayein " + itemName + " ke saath kya hua - masalan kya woh kharab thi, naqis thi, ghalat cheez thi, ya ghaib hai?";
            case CODE_SWITCH -> "Please batayein " + itemName + " ke saath kya hua - for example damaged, defective, wrong item, ya missing?";
            default -> "Could you tell me what happened with the " + itemName + " - for example was it damaged, defective, the wrong item, or missing?";
        };
    }

    private String quantityRequired(ConversationLanguage lang, Map<String, String> metadata) {
        String itemName = metadata.getOrDefault("itemName", "").strip();
        String max = metadata.getOrDefault("maxReturnableQuantity", "").strip();
        String maxPart = max.isEmpty() ? "" : switch (lang) {
            case URDU -> " زیادہ سے زیادہ " + max + " واپس ہو سکتی ہیں۔";
            case ROMAN_URDU -> " Zyada se zyada " + max + " wapas ho sakti hain.";
            case CODE_SWITCH -> " Maximum " + max + " return ho sakti hain.";
            default -> " Up to " + max + " can still be returned.";
        };
        return switch (lang) {
            case URDU -> "آپ " + itemName + " کی کتنی اکائیاں واپس کرنا چاہتے ہیں؟" + maxPart;
            case ROMAN_URDU -> "Aap " + itemName + " ki kitni units wapas karna chahte hain?" + maxPart;
            case CODE_SWITCH -> "Aap " + itemName + " ki kitni units return karna chahte hain?" + maxPart;
            default -> "How many units of the " + itemName + " would you like to return?" + maxPart;
        };
    }

    /**
     * The deferred promotion hit an unexpected technical failure. The
     * already-completed active mutation keeps its own rendered result (the
     * caller composes this notice after it); the queued request stays
     * queued. No exception text, no internal identifiers.
     */
    /**
     * Pass 2D-B final cleanup: the queued request could not be started after
     * the first action succeeded. The notice must NOT promise automatic
     * processing later - it communicates only that the queued request could
     * not be started, that it remains saved, and that the customer may retry
     * or continue it later. It is always composed after the successful
     * first-action text.
     */
    private String promotionFailed(ConversationLanguage lang) {
        return switch (lang) {
            case URDU -> "آپ کی قطار والی درخواست شروع نہیں ہو سکی - وہ محفوظ ہے، آپ بعد میں اسے جاری رکھنے کے لیے کہہ سکتے ہیں۔";
            case ROMAN_URDU -> "Aap ki queued darkhwast shuru nahi ho saki - woh mehfooz hai, aap baad mein usay continue karne ke liye keh saktay hain.";
            case CODE_SWITCH -> "Aapki queued request start nahi ho saki - woh saved hai, aap baad mein continue karne ke liye keh saktay hain.";
            default -> "I couldn't start your queued request - it's still saved, and you can ask me to continue with it later.";
        };
    }

    private String fallback(ConversationLanguage lang) {
        return switch (lang) {
            case URDU -> "کوئی مسئلہ پیش آیا ہے۔ براہ کرم دوبارہ کوشش کریں یا انسانی نمائندے سے رابطہ کریں۔";
            case ROMAN_URDU -> "Koi masla pesh aaya hai. Barah-e-karam dobara koshish karein ya insani numainde se rabta karein.";
            case CODE_SWITCH -> "Kuch issue aaya hai. Please dobara try karein ya human agent se baat karein.";
            default -> "Something went wrong. Please try again or ask for a human agent.";
        };
    }
}
