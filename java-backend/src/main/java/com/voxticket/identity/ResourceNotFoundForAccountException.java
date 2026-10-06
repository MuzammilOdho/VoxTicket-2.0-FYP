package com.voxticket.identity;

/**
 * Thrown by every customer-scoped lookup when the requested business
 * reference either does not exist at all, or exists but is not owned by the
 * calling customer (spec §10). These two cases are deliberately
 * indistinguishable outside this class - there is no field or message
 * variant that reveals which case occurred, because that distinction would
 * itself be a data leak (it would confirm someone else's reference is real).
 */
public class ResourceNotFoundForAccountException extends RuntimeException {

    public ResourceNotFoundForAccountException(String resourceType, String reference) {
        super(resourceType + "_NOT_FOUND_FOR_ACCOUNT: " + reference);
    }
}