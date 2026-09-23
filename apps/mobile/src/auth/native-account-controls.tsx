import { Alert, Pressable, StyleSheet, Text, View } from 'react-native';
import { tokens } from '@routiqo/design-tokens';
import { useNativeAccount } from './native-account-provider';

const c = tokens.colors;
export function NativeAccountControls() {
  const session = useNativeAccount();
  return (
    <View style={styles.section}>
      <Text style={styles.heading}>Routiqo account</Text>
      {!session.configured ? (
        <Text style={styles.text}>
          Sign-in is unavailable in this build. Local plans and saved places still work.
        </Text>
      ) : session.deletionCleanupPending ? (
        <>
          <Text style={styles.text}>
            The server account was deleted. Finish local journey cleanup.
          </Text>
          <Pressable
            accessibilityRole="button"
            style={styles.button}
            disabled={session.busy}
            onPress={() => void session.retryDeletionCleanup()}
          >
            <Text style={styles.buttonText}>Retry local cleanup</Text>
          </Pressable>
        </>
      ) : session.restoring ? (
        <Text style={styles.text}>Verifying saved session…</Text>
      ) : session.accountId ? (
        <>
          <Text style={styles.text}>
            Signed in. Server journeys are kept separate from your local plans.
          </Text>
          <Pressable
            accessibilityRole="button"
            style={styles.button}
            disabled={session.busy}
            onPress={() => void session.signOut()}
          >
            <Text style={styles.buttonText}>{session.busy ? 'Please wait…' : 'Sign out'}</Text>
          </Pressable>
          {session.recentRequired ? (
            <Pressable
              accessibilityRole="button"
              style={styles.button}
              disabled={session.busy || !session.online}
              onPress={() => void session.reauthenticateForDeletion()}
            >
              <Text style={styles.buttonText}>Verify with Google for deletion</Text>
            </Pressable>
          ) : null}
          <Pressable
            accessibilityRole="button"
            style={styles.destructive}
            disabled={session.busy || !session.online}
            onPress={() =>
              Alert.alert(
                'Delete Routiqo account?',
                'Your server journeys and account will be deleted. Local plans and your Google account remain. This cannot be undone.',
                [
                  { text: 'Keep account', style: 'cancel' },
                  {
                    text: 'Delete account',
                    style: 'destructive',
                    onPress: () => void session.deleteAccount(),
                  },
                ],
              )
            }
          >
            <Text style={styles.destructiveText}>Delete Routiqo account</Text>
          </Pressable>
        </>
      ) : (
        <>
          <Text style={styles.text}>Sign in with Google to start or recover server journeys.</Text>
          <Pressable
            accessibilityRole="button"
            style={styles.primary}
            disabled={session.busy || !session.online}
            onPress={() => void session.signIn()}
          >
            <Text style={styles.primaryText}>
              {session.busy ? 'Signing in…' : 'Sign in with Google'}
            </Text>
          </Pressable>
          {session.error ? (
            <Pressable
              accessibilityRole="button"
              style={styles.button}
              onPress={() => void session.restore()}
            >
              <Text style={styles.buttonText}>Retry session check</Text>
            </Pressable>
          ) : null}
        </>
      )}
      {session.error ? (
        <Text accessibilityRole="alert" style={styles.error}>
          {session.error}
        </Text>
      ) : null}
      {!session.online && session.configured ? (
        <Text style={styles.text}>
          Offline. Existing plans stay here; account access needs a connection.
        </Text>
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  section: {
    paddingVertical: 14,
    borderBottomWidth: 1,
    borderBottomColor: c.line,
    marginBottom: 18,
  },
  heading: { color: c.ink, fontSize: 20, fontWeight: '600', marginBottom: 8 },
  text: { color: c.muted, fontSize: 14, lineHeight: 21, marginBottom: 12 },
  error: { color: c.danger, fontSize: 14, lineHeight: 21, marginTop: 8 },
  primary: {
    backgroundColor: c.ink,
    minHeight: tokens.touchTarget,
    padding: 12,
    borderRadius: tokens.radius.sm,
    alignItems: 'center',
    justifyContent: 'center',
  },
  primaryText: { color: c.surface, fontSize: 15, fontWeight: '600' },
  button: {
    minHeight: tokens.touchTarget,
    padding: 12,
    borderWidth: 1,
    borderColor: c.line,
    borderRadius: tokens.radius.sm,
    alignItems: 'center',
    justifyContent: 'center',
  },
  buttonText: { color: c.ink, fontSize: 15, fontWeight: '600' },
  destructive: {
    minHeight: tokens.touchTarget,
    padding: 12,
    marginTop: 12,
    alignItems: 'center',
    justifyContent: 'center',
  },
  destructiveText: { color: c.danger, fontSize: 15, fontWeight: '600' },
});
