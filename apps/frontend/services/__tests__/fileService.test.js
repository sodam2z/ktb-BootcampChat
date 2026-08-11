import { afterEach, describe, expect, it, vi } from 'vitest';
import axiosInstance from '../axios';
import fileService from '../fileService';

vi.mock('../../components/Toast', () => ({
  Toast: {
    error: vi.fn(),
  },
}));

vi.mock('../axios', () => ({
  default: {
    post: vi.fn(),
  },
}));

describe('fileService', () => {
  afterEach(() => {
    vi.restoreAllMocks();
    vi.clearAllMocks();
  });

  it('handles upload size limit errors without logging console errors', () => {
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {});

    const result = fileService.handleUploadError(
      Object.assign(new Error('파일 크기는 5MB를 초과할 수 없습니다.'), {
        status: 413,
      })
    );

    expect(result).toEqual({
      success: false,
      message: '파일 크기는 5MB를 초과할 수 없습니다.',
    });
    expect(consoleError).not.toHaveBeenCalled();
  });

  it('uses the observable multipart upload contract by default', async () => {
    const file = new File(['image'], 'profile.jpg', { type: 'image/jpeg' });

    axiosInstance.post.mockResolvedValueOnce({
        data: {
          success: true,
          file: {
            filename: 'stored-profile.jpg',
          },
        },
      });

    const result = await fileService.uploadFile(file);

    expect(axiosInstance.post).toHaveBeenCalledTimes(1);
    expect(axiosInstance.post.mock.calls[0][0]).toContain('/api/files/upload');
    expect(axiosInstance.post.mock.calls[0][1]).toBeInstanceOf(FormData);
    expect(result).toMatchObject({
      success: true,
      data: {
        file: {
          filename: 'stored-profile.jpg',
        },
      },
    });
  });
});
