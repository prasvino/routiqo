# Authorization
Foundation denies all undeclared protected paths, independent of client-provided IDs.
Future authorization applies at object/action/subscription level using verified actor identity. A valid socket is not permission to subscribe to arbitrary rooms. Room location claims are not proof of membership. Admin access requires separate policy and audit.
Tests must cover cross-user journey access, expired rooms, block enforcement and revoked sessions.

