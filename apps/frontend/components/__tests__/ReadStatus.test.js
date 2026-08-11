import React from 'react';
import { act, render } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import socketClient from '@/lib/socket/socketClient';
import ReadStatus from '../ReadStatus';

vi.mock('@/lib/socket/socketClient', () => ({
  default: {
    canSend: vi.fn(() => true),
    markMessagesAsRead: vi.fn(),
  },
}));

describe('ReadStatus', () => {
  let observers;

  beforeEach(() => {
    vi.useFakeTimers();
    vi.clearAllMocks();
    observers = [];

    global.IntersectionObserver = vi.fn(function IntersectionObserver(callback) {
      this.observe = vi.fn();
      this.disconnect = vi.fn();
      this.trigger = (isIntersecting = true) => {
        callback([{ isIntersecting }]);
      };
      observers.push(this);
    });
  });

  afterEach(() => {
    vi.runOnlyPendingTimers();
    vi.useRealTimers();
    delete global.IntersectionObserver;
  });

  const renderReadStatus = (messageId) => {
    const messageRef = { current: document.createElement('div') };

    return render(
      <ReadStatus
        messageType="text"
        participants={[{ id: 'user-1' }]}
        readers={[]}
        messageId={messageId}
        messageRef={messageRef}
        currentUserId="user-1"
      />
    );
  };

  it('batches visible messages into one read event', () => {
    renderReadStatus('message-1');
    renderReadStatus('message-2');
    renderReadStatus('message-3');

    act(() => {
      observers.forEach((observer) => observer.trigger(true));
    });

    expect(socketClient.markMessagesAsRead).not.toHaveBeenCalled();

    act(() => {
      vi.advanceTimersByTime(300);
    });

    expect(socketClient.markMessagesAsRead).toHaveBeenCalledTimes(1);
    expect(socketClient.markMessagesAsRead).toHaveBeenCalledWith([
      'message-1',
      'message-2',
      'message-3',
    ]);
  });

  it('does not queue read events while the socket cannot send', () => {
    socketClient.canSend.mockReturnValue(false);
    renderReadStatus('message-1');

    act(() => {
      observers[0].trigger(true);
      vi.advanceTimersByTime(300);
    });

    expect(socketClient.markMessagesAsRead).not.toHaveBeenCalled();
  });
});
