import { afterEach, describe, expect, it, vi } from 'vitest';
import { optimizeProfileImage } from '../optimizeProfileImage';

describe('optimizeProfileImage', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('지원하지 않는 환경에서는 원본을 유지한다', async () => {
    vi.stubGlobal('createImageBitmap', undefined);
    const file = new File(['original'], 'profile.jpg', { type: 'image/jpeg' });

    await expect(optimizeProfileImage(file)).resolves.toBe(file);
  });

  it('512px 이하 WebP로 축소하고 더 작은 파일을 반환한다', async () => {
    const bitmap = { width: 1024, height: 512, close: vi.fn() };
    vi.stubGlobal('createImageBitmap', vi.fn().mockResolvedValue(bitmap));
    const context = { drawImage: vi.fn() };
    const canvas = {
      width: 0,
      height: 0,
      getContext: vi.fn().mockReturnValue(context),
      toBlob: vi.fn((callback) => callback(new Blob(['small'], { type: 'image/webp' }))),
    };
    vi.spyOn(document, 'createElement').mockReturnValue(canvas);
    const file = new File([new Uint8Array(1024)], 'profile.jpg', { type: 'image/jpeg' });

    const optimized = await optimizeProfileImage(file);

    expect(canvas.width).toBe(512);
    expect(canvas.height).toBe(256);
    expect(context.drawImage).toHaveBeenCalledWith(bitmap, 0, 0, 512, 256);
    expect(optimized.name).toBe('profile.webp');
    expect(optimized.type).toBe('image/webp');
    expect(optimized.size).toBeLessThan(file.size);
    expect(bitmap.close).toHaveBeenCalled();
  });

  it('변환 결과가 원본보다 크면 원본을 유지한다', async () => {
    const bitmap = { width: 256, height: 256, close: vi.fn() };
    vi.stubGlobal('createImageBitmap', vi.fn().mockResolvedValue(bitmap));
    const canvas = {
      getContext: vi.fn().mockReturnValue({ drawImage: vi.fn() }),
      toBlob: vi.fn((callback) => callback(new Blob([new Uint8Array(20)], { type: 'image/webp' }))),
    };
    vi.spyOn(document, 'createElement').mockReturnValue(canvas);
    const file = new File(['tiny'], 'profile.png', { type: 'image/png' });

    await expect(optimizeProfileImage(file)).resolves.toBe(file);
  });
});
