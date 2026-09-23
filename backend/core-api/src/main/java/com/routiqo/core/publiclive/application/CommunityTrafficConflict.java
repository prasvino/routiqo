package com.routiqo.core.publiclive.application;

/** Another request already owns the V3 account/window slot or daily budget. */
public final class CommunityTrafficConflict extends RuntimeException {
    public CommunityTrafficConflict() { super("Community traffic share conflicts with a prior action"); }
}
