import React from 'react';
import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import ChatMessages, { getChronologicalMessages } from '../ChatMessages';

vi.mock('../../hooks/useInfiniteScroll', () => ({
  useInfiniteScroll: () => ({ sentinelRef: { current: null } }),
}));

vi.mock('../../hooks/useAutoScroll', () => ({
  useAutoScroll: () => ({
    containerRef: { current: null },
    scrollToBottom: vi.fn(),
    isNearBottom: true,
  }),
}));

vi.mock('../SystemMessage', () => ({
  default: ({ msg }) => React.createElement('div', { 'data-testid': 'message' }, msg.content),
}));

vi.mock('../FileMessage', () => ({
  default: ({ msg }) => React.createElement('div', { 'data-testid': 'message' }, msg.content),
}));

vi.mock('../UserMessage', () => ({
  default: ({ msg }) => React.createElement('div', { 'data-testid': 'message' }, msg.content),
}));

describe('ChatMessages', () => {
  it('reuses an already chronological message array', () => {
    const messages = [
      { _id: 'early', timestamp: '2026-06-20T11:00:00.000Z' },
      { _id: 'late', timestamp: '2026-06-20T12:00:00.000Z' },
    ];

    expect(getChronologicalMessages(messages)).toBe(messages);
  });

  it('sorts only out-of-order messages without mutating the input array', () => {
    const messages = [
      { _id: 'late', timestamp: '2026-06-20T12:00:00.000Z' },
      { _id: 'early', timestamp: '2026-06-20T11:00:00.000Z' },
    ];

    const sortedMessages = getChronologicalMessages(messages);

    expect(sortedMessages).not.toBe(messages);
    expect(sortedMessages.map((message) => message._id)).toEqual(['early', 'late']);
    expect(messages.map((message) => message._id)).toEqual(['late', 'early']);
  });

  it('renders messages sorted by timestamp without mutating the input array', () => {
    const messages = [
      {
        _id: 'late',
        content: 'late message',
        timestamp: '2026-06-20T12:00:00.000Z',
        sender: { _id: 'other' },
      },
      {
        _id: 'early',
        content: 'early message',
        timestamp: '2026-06-20T11:00:00.000Z',
        sender: { _id: 'other' },
      },
    ];
    const originalOrder = messages.map((message) => message._id);

    render(
      React.createElement(ChatMessages, {
        messages,
        currentUser: { id: 'me' },
        hasMoreMessages: false,
      })
    );

    expect(screen.getAllByTestId('message').map((node) => node.textContent)).toEqual([
      'early message',
      'late message',
    ]);
    expect(messages.map((message) => message._id)).toEqual(originalOrder);
  });

  it('keeps optimized message wrappers discoverable in the rendered DOM', () => {
    render(
      React.createElement(ChatMessages, {
        messages: [
          {
            _id: 'message-1',
            content: 'discoverable message',
            timestamp: '2026-06-20T11:00:00.000Z',
            sender: { _id: 'other' },
          },
        ],
        currentUser: { id: 'me' },
        hasMoreMessages: true,
        loadingMessages: true,
      })
    );

    const message = screen.getByText('discoverable message');
    const optimizedWrapper = message.closest('[style]');

    expect(message).toBeInTheDocument();
    expect(optimizedWrapper).toHaveStyle({
      contentVisibility: 'auto',
      containIntrinsicSize: '1px 96px',
    });
    expect(screen.getByText('이전 메시지를 불러오는 중...')).toBeInTheDocument();
  });
});
