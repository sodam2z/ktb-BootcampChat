const DEFAULT_MAX_DIMENSION = 512;
const DEFAULT_QUALITY = 0.82;

const canvasToBlob = (canvas, type, quality) =>
  new Promise((resolve) => canvas.toBlob(resolve, type, quality));

/**
 * 프로필 이미지를 브라우저에서 축소한다. 지원하지 않는 브라우저나 변환 결과가 더 큰 경우 원본을 유지한다.
 * 서버의 5MB 검증은 별도로 유지하므로 이 함수는 보안 경계가 아니라 전송량 최적화다.
 */
export async function optimizeProfileImage(
  file,
  { maxDimension = DEFAULT_MAX_DIMENSION, quality = DEFAULT_QUALITY } = {}
) {
  if (typeof createImageBitmap !== 'function' || typeof document === 'undefined') {
    return file;
  }

  const bitmap = await createImageBitmap(file);
  try {
    const scale = Math.min(1, maxDimension / Math.max(bitmap.width, bitmap.height));
    const width = Math.max(1, Math.round(bitmap.width * scale));
    const height = Math.max(1, Math.round(bitmap.height * scale));
    const canvas = document.createElement('canvas');
    canvas.width = width;
    canvas.height = height;
    const context = canvas.getContext('2d');
    if (!context) return file;

    context.drawImage(bitmap, 0, 0, width, height);
    const blob = await canvasToBlob(canvas, 'image/webp', quality);
    if (!blob || blob.size >= file.size) return file;

    const baseName = file.name.replace(/\.[^.]+$/, '') || 'profile';
    return new File([blob], `${baseName}.webp`, {
      type: 'image/webp',
      lastModified: Date.now(),
    });
  } finally {
    bitmap.close?.();
  }
}
