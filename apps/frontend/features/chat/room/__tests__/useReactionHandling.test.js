import { act, renderHook } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { Toast } from '@/components/Toast';
import socketClient from '@/lib/socket/socketClient';
import { useReactionHandling } from '../useReactionHandling';

vi.mock('@/lib/socket/socketClient', () => ({
  default: {
    canSend: vi.fn(() => true),
    sendMessageReaction: vi.fn(),
  },
}));

vi.mock('@/components/Toast', () => ({
  Toast: { error: vi.fn() },
}));

const currentUser = { id: 'user-1' };
const messages = [{ _id: 'message-1', reactions: { '👍': ['user-1'] } }];

describe('useReactionHandling', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    socketClient.canSend.mockReturnValue(true);
  });

  it('delegates reaction add to socketClient', async () => {
    const setMessages = vi.fn();
    const { result } = renderHook(() =>
      useReactionHandling({ currentUser, messages, setMessages })
    );

    await act(async () => {
      await result.current.handleReactionAdd('message-1', '👍');
    });

    expect(socketClient.sendMessageReaction).toHaveBeenCalledWith(
      'message-1',
      '👍',
      'add',
    );
  });

  it('delegates reaction remove to socketClient', async () => {
    const setMessages = vi.fn();
    const { result } = renderHook(() =>
      useReactionHandling({ currentUser, messages, setMessages })
    );

    await act(async () => {
      await result.current.handleReactionRemove('message-1', '👍');
    });

    expect(socketClient.sendMessageReaction).toHaveBeenCalledWith(
      'message-1',
      '👍',
      'remove',
    );
  });

  it('does not send reaction add when the socket client cannot send', async () => {
    const setMessages = vi.fn();
    socketClient.canSend.mockReturnValue(false);
    const { result } = renderHook(() =>
      useReactionHandling({ currentUser, messages, setMessages })
    );

    await act(async () => {
      await result.current.handleReactionAdd('message-1', '👍');
    });

    expect(socketClient.sendMessageReaction).not.toHaveBeenCalled();
    expect(Toast.error).toHaveBeenCalledWith('리액션 추가에 실패했습니다.');
  });

  it('does not send reaction remove when the socket client cannot send', async () => {
    const setMessages = vi.fn();
    socketClient.canSend.mockReturnValue(false);
    const { result } = renderHook(() =>
      useReactionHandling({ currentUser, messages, setMessages })
    );

    await act(async () => {
      await result.current.handleReactionRemove('message-1', '👍');
    });

    expect(socketClient.sendMessageReaction).not.toHaveBeenCalled();
    expect(Toast.error).toHaveBeenCalledWith('리액션 제거에 실패했습니다.');
  });

  it('rolls back to the original reactions when sending a reaction fails', async () => {
    const setMessages = vi.fn();
    socketClient.sendMessageReaction.mockRejectedValueOnce(new Error('send failed'));
    const { result } = renderHook(() =>
      useReactionHandling({ currentUser, messages, setMessages })
    );

    await act(async () => {
      await result.current.handleReactionAdd('message-1', 'fire');
    });

    const rollbackUpdate = setMessages.mock.calls.at(-1)[0];
    expect(rollbackUpdate([
      { _id: 'message-1', reactions: { fire: ['user-1'] } },
      { _id: 'message-2', reactions: {} },
    ])).toEqual([
      { _id: 'message-1', reactions: messages[0].reactions },
      { _id: 'message-2', reactions: {} },
    ]);
  });
});
