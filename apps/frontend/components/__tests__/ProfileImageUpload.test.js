import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import axios from 'axios';
import api from '@/lib/api/client';
import ProfileImageUpload from '../ProfileImageUpload';

vi.mock('axios', () => ({ default: { put: vi.fn() } }));
vi.mock('@/lib/api/client', () => ({ default: { post: vi.fn(), delete: vi.fn() } }));
vi.mock('@/contexts/AuthContext', () => ({
  useAuth: () => ({ user: { id: 'user-1', token: 'token', email: 'user@example.com' } }),
}));
vi.mock('@/lib/auth/authStorage', () => ({ saveStoredUser: vi.fn() }));
vi.mock('@/components/Toast', () => ({ Toast: { success: vi.fn(), error: vi.fn() } }));

describe('ProfileImageUpload', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.stubGlobal('URL', {
      ...URL,
      createObjectURL: vi.fn(() => 'blob:preview'),
      revokeObjectURL: vi.fn(),
    });
  });

  it('uploads profile bytes directly to S3 before completing metadata', async () => {
    api.post
      .mockResolvedValueOnce({ data: { uploadUrl: 'https://s3.example/upload', key: 'profiles/user-1_x.jpg' } })
      .mockResolvedValueOnce({ data: { imageUrl: '/api/files/profiles/user-1_x.jpg' } });
    axios.put.mockResolvedValueOnce({ status: 200 });
    const onImageChange = vi.fn();
    render(<ProfileImageUpload currentImage="" onImageChange={onImageChange} />);

    const file = new File(['image-bytes'], 'profile.jpg', { type: 'image/jpeg' });
    fireEvent.change(screen.getByTestId('profile-image-file-input'), {
      target: { files: [file] },
    });

    await waitFor(() => expect(onImageChange).toHaveBeenCalledWith('/api/files/profiles/user-1_x.jpg'));
    expect(axios.put).toHaveBeenCalledWith(
      'https://s3.example/upload',
      file,
      expect.objectContaining({ timeout: 30000 }),
    );
    expect(api.post).toHaveBeenNthCalledWith(
      2,
      '/api/users/profile-image/complete',
      expect.objectContaining({ key: 'profiles/user-1_x.jpg' }),
      { skipRetry: true },
    );
  });
});
