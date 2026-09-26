package com.routiqo.core.moderation.application;

/** The operator lacks a current grant for the requested moderation action. */
public final class OperatorNotPermitted extends RuntimeException {
    public OperatorNotPermitted() { super(null, null, false, false); }
}
