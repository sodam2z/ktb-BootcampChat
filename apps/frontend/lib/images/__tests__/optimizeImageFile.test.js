import { afterEach, describe, expect, it, vi } from 'vitest';
import { optimizeImageFile } from '../optimizeImageFile';

describe('optimizeImageFile', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('returns non-image files unchanged', async () => {
    const file = new File(['pdf'], 'document.pdf', { type: 'application/pdf' });

    await expect(optimizeImageFile(file)).resolves.toBe(file);
  });

  it('keeps GIF files unchanged by default', async () => {
    const file = new File(['gif'], 'animation.gif', { type: 'image/gif' });

    await expect(optimizeImageFile(file)).resolves.toBe(file);
  });

  it('resizes images to the requested max dimension and returns a smaller WebP file', async () => {
    const bitmap = { width: 4000, height: 3000, close: vi.fn() };
    vi.stubGlobal('createImageBitmap', vi.fn().mockResolvedValue(bitmap));
    const context = { drawImage: vi.fn() };
    const canvas = {
      width: 0,
      height: 0,
      getContext: vi.fn().mockReturnValue(context),
      toBlob: vi.fn((callback) => callback(new Blob(['small'], { type: 'image/webp' }))),
    };
    vi.spyOn(document, 'createElement').mockReturnValue(canvas);
    const file = new File([new Uint8Array(1024)], 'photo.jpg', { type: 'image/jpeg' });

    const optimized = await optimizeImageFile(file, {
      maxDimension: 1600,
      quality: 0.84,
    });

    expect(canvas.width).toBe(1600);
    expect(canvas.height).toBe(1200);
    expect(context.drawImage).toHaveBeenCalledWith(bitmap, 0, 0, 1600, 1200);
    expect(optimized.name).toBe('photo.webp');
    expect(optimized.type).toBe('image/webp');
    expect(optimized.size).toBeLessThan(file.size);
    expect(bitmap.close).toHaveBeenCalled();
  });

  it('returns the original file when the optimized output is larger', async () => {
    const bitmap = { width: 400, height: 300, close: vi.fn() };
    vi.stubGlobal('createImageBitmap', vi.fn().mockResolvedValue(bitmap));
    const canvas = {
      getContext: vi.fn().mockReturnValue({ drawImage: vi.fn() }),
      toBlob: vi.fn((callback) => callback(new Blob([new Uint8Array(20)], { type: 'image/webp' }))),
    };
    vi.spyOn(document, 'createElement').mockReturnValue(canvas);
    const file = new File(['tiny'], 'tiny.png', { type: 'image/png' });

    await expect(optimizeImageFile(file)).resolves.toBe(file);
    expect(bitmap.close).toHaveBeenCalled();
  });
});
