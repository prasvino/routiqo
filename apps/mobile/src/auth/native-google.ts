import type { GoogleIdentityPort } from './native-account';

const googleClientId = process.env.EXPO_PUBLIC_ROUTIQO_GOOGLE_WEB_CLIENT_ID;
export const nativeGoogle: GoogleIdentityPort = {
  async idToken(nonce) {
    if (
      !googleClientId ||
      !/^[0-9]+-[A-Za-z0-9_-]+\.apps\.googleusercontent\.com$/.test(googleClientId)
    )
      throw new Error('Google sign-in is not configured.');
    if (!/^[a-f0-9]{64}$/.test(nonce)) throw new Error('Google sign-in challenge is invalid.');
    try {
      const {
        GoogleOneTapSignIn,
        isSuccessResponse,
        isNoSavedCredentialFoundResponse,
        isCancelledResponse,
      } = await import('react-native-nitro-google-signin');
      GoogleOneTapSignIn.configure({
        webClientId: googleClientId,
        nonce,
        offlineAccess: false,
        autoSelectOnSignIn: false,
      });
      await GoogleOneTapSignIn.checkPlayServices();
      let response = await GoogleOneTapSignIn.signIn();
      if (isNoSavedCredentialFoundResponse(response))
        response = await GoogleOneTapSignIn.createAccount();
      if (isCancelledResponse(response)) return null;
      if (!isSuccessResponse(response) || !response.data?.idToken)
        throw new Error('Google sign-in failed.');
      return response.data.idToken;
    } catch {
      throw new Error('Google sign-in could not finish. Try again.');
    }
  },
};
