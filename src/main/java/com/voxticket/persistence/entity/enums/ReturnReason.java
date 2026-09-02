package com.voxticket.persistence.entity.enums;

/**
 * NOT specified verbatim in the spec (only the field name is given). Default
 * taxonomy for why a customer is returning an item - distinct from
 * ClaimReason because returns include voluntary re asons. Flag if you want a
 * different taxonomy.
 */
public enum ReturnReason { CHANGED_MIND, WRONG_SIZE, NOT_AS_DESCRIBED, DEFECTIVE, DAMAGED, OTHER }