'use client';

import { useEffect } from 'react';
import { usePathname, useRouter } from 'next/navigation';
import AuthRequiredFallback from '@/components/AuthRequiredFallback';
import ChatHeader from '@/components/ChatHeader';
import { useAuth } from '@/contexts/AuthContext';
import ChatRoomView from '@/features/chat/room/ChatRoomView';
import { useRoomId } from '@/hooks/useRoomId';

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

export default function ChatRoomPage() {
  const router = useRouter();
  const pathname = usePathname();
  const { isAuthenticated, isLoading } = useAuth();
  const roomId = useRoomId();

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
      <ChatRoomView
        roomId={roomId}
        onNavigate={router.push}
        onReplace={router.replace}
        asPath={pathname}
      />
    </>
  );
}
