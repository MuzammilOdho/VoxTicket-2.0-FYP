package com.voxticket.conversation;

/**
 * P2.3: how a new utterance relates to the turn it interrupted.
 *
 * <ul>
 *   <li>{@code CORRECTION} - contradicts or overrides part of the previous
 *       request ("no, order 1234, not 5678").</li>
 *   <li>{@code CONTINUATION} - adds to the previous request ("and also the
 *       charger").</li>
 *   <li>{@code NEW_INTENT} - unrelated to the previous request ("what's your
 *       return policy?"). Also the safe fallback when a proposed
 *       classification fails validation - never mis-merge.</li>
 *   <li>{@code WITHDRAWAL} - abandons the previous request ("never mind",
 *       "forget it").</li>
 * </ul>
 */
public enum InterruptionClass {
    CORRECTION,
    CONTINUATION,
    NEW_INTENT,
    WITHDRAWAL
}
