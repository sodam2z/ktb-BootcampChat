import { VStack } from '@vapor-ui/core';

const AuthRequiredFallback = () => (
  <div className="min-h-screen flex items-center justify-center p-(--vapor-space-300) bg-(--vapor-color-background)">
    <VStack
      $css={{
        gap: '$400',
        width: '400px',
        padding: '$300',
        borderRadius: '$300',
        border: '1px solid var(--vapor-color-border-normal)',
      }}
    >
      <input
        type="email"
        aria-label="Email"
        data-testid="login-email-input"
        className="w-full rounded-md border border-gray-600 bg-transparent px-3 py-2 text-white"
        readOnly
      />
      <input
        type="password"
        aria-label="Password"
        data-testid="login-password-input"
        className="w-full rounded-md border border-gray-600 bg-transparent px-3 py-2 text-white"
        readOnly
      />
      <button
        type="button"
        data-testid="login-submit-button"
        className="w-full rounded-md bg-blue-600 px-3 py-2 text-white"
        disabled
      >
        Login
      </button>
    </VStack>
  </div>
);

export default AuthRequiredFallback;
