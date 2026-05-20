package com.yucareux.townfolk.compat.create;

/**
 * Default {@link CreateBridge} implementation used when Create isn't
 * installed (or its bridge impl failed to load). Every method
 * inherits the conservative defaults from the interface; this class
 * exists so {@link CreateBridge#get()} always returns a real object
 * (no null checks at call sites).
 */
public final class NoopCreateBridge implements CreateBridge {
   @Override
   public boolean isAvailable() { return false; }
}
