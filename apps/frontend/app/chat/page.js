'use client';

import { useEffect } from 'react';
import { usePathname, useRouter } from 'next/navigation';
import AuthRequiredFallback from '@/components/AuthRequiredFallback';
import ChatHeader from '@/components/ChatHeader';
import { useAuth } from '@/contexts/AuthContext';
import ChatRoomsView from '@/features/chat/rooms/ChatRoomsView';

const LoadingState = () => (
  <div
    style={{
      display: 'flex',
      alignItems: 'center',
      justifyContent: 'center',
      height: '100vh',
      backgroundColor: 'var(--vapor-color-background)',
      color: 'var(--vapor-color-text-primary)',
    }}
  >
    <div>Loading...</div>
  </div>
);

export default function ChatPage() {
  const router = useRouter();
  const pathname = usePathname();
  const { isAuthenticated, isLoading } = useAuth();

  useEffect(() => {
    if (!isLoading && !isAuthenticated) {
      const redirectTimer = window.setTimeout(() => {
        router.replace(`/?redirect=${pathname}`);
      }, 100);

      return () => window.clearTimeout(redirectTimer);
    }
  }, [isAuthenticated, isLoading, pathname, router]);

  if (isLoading) {
    return <LoadingState />;
  }

  if (!isAuthenticated) {
    return <AuthRequiredFallback />;
  }

  return (
    <>
      <ChatHeader />
      <ChatRoomsView router={router} />
    </>
  );
}
