import React from 'react';
import { act, render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import FileMessage from '../FileMessage';
import fileService from '@/services/fileService';

vi.mock('@/contexts/AuthContext', () => ({
  useAuth: () => ({
    user: {
      token: 'token-1',
      sessionId: 'session-1',
    },
  }),
}));

vi.mock('@/services/fileService', () => ({
  default: {
    getPreviewUrl: vi.fn(() => '/api/files/view/image.jpg?token=token-1'),
    getFileUrl: vi.fn(() => '/api/files/view/image.jpg'),
    formatFileSize: vi.fn(() => '1 KB'),
  },
}));

vi.mock('../CustomAvatar', () => ({
  default: () => <div data-testid="avatar" />,
}));

vi.mock('../MessageActions', () => ({
  default: () => <div data-testid="message-actions" />,
}));

vi.mock('../FileActions', () => ({
  default: () => <div data-testid="file-actions" />,
}));

vi.mock('../ReadStatus', () => ({
  default: () => <div data-testid="read-status" />,
}));

describe('FileMessage', () => {
  let observers;

  beforeEach(() => {
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

  const imageMessage = {
    _id: 'message-1',
    content: 'uploaded image',
    timestamp: '2026-08-12T00:00:00.000Z',
    sender: { id: 'user-2', name: 'Uploader' },
    file: {
      id: 'file-1',
      filename: 'image.jpg',
      originalname: 'image.jpg',
      mimetype: 'image/jpeg',
      size: 1024,
    },
    readers: [],
  };

  it('renders the file message immediately and loads preview URL only near viewport', () => {
    render(
      <FileMessage
        msg={imageMessage}
        currentUser={{ id: 'user-1' }}
        room={{ participants: [{ id: 'user-1' }] }}
      />
    );

    expect(screen.getByTestId('file-message-container')).toHaveTextContent('uploaded image');
    expect(screen.getByTestId('file-image-preview')).toHaveAttribute(
      'src',
      '/images/placeholder-image.png'
    );
    expect(fileService.getPreviewUrl).not.toHaveBeenCalled();

    act(() => {
      observers[0].trigger(true);
    });

    expect(fileService.getPreviewUrl).toHaveBeenCalledWith(
      imageMessage.file,
      'token-1',
      'session-1',
      true
    );
    expect(screen.getByTestId('file-image-preview')).toHaveAttribute(
      'src',
      '/api/files/view/image.jpg?token=token-1'
    );
  });
});
