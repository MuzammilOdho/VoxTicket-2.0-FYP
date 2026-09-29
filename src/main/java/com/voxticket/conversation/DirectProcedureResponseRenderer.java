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

    private String fallback(ConversationLanguage lang) {
        return switch (lang) {
            case URDU -> "کوئی مسئلہ پیش آیا ہے۔ براہ کرم دوبارہ کوشش کریں یا انسانی نمائندے سے رابطہ کریں۔";
            case ROMAN_URDU -> "Koi masla pesh aaya hai. Barah-e-karam dobara koshish karein ya insani numainde se rabta karein.";
            case CODE_SWITCH -> "Kuch issue aaya hai. Please dobara try karein ya human agent se baat karein.";
            default -> "Something went wrong. Please try again or ask for a human agent.";
        };
    }
}
