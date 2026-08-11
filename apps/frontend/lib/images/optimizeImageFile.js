const DEFAULT_MAX_DIMENSION = 1600;
const DEFAULT_QUALITY = 0.84;
const DEFAULT_OUTPUT_TYPE = 'image/webp';
const DEFAULT_SKIP_TYPES = new Set(['image/gif']);

const EXTENSION_BY_TYPE = {
  'image/webp': 'webp',
  'image/jpeg': 'jpg',
  'image/png': 'png',
};

const canvasToBlob = (canvas, type, quality) =>
  new Promise((resolve) => canvas.toBlob(resolve, type, quality));

const hasSkippedType = (skipTypes, type) => {
  if (!skipTypes) return false;
  if (typeof skipTypes.has === 'function') return skipTypes.has(type);
  if (Array.isArray(skipTypes)) return skipTypes.includes(type);
  return false;
};

const getOutputName = (name, type) => {
  const extension = EXTENSION_BY_TYPE[type];
  if (!extension) return name;

  const baseName = name?.replace(/\.[^.]+$/, '') || 'image';
  return `${baseName}.${extension}`;
};

export async function optimizeImageFile(
  file,
  {
    maxDimension = DEFAULT_MAX_DIMENSION,
    quality = DEFAULT_QUALITY,
    outputType = DEFAULT_OUTPUT_TYPE,
    skipTypes = DEFAULT_SKIP_TYPES,
  } = {}
) {
  if (!file?.type?.startsWith('image/')) return file;
  if (hasSkippedType(skipTypes, file.type)) return file;
  if (typeof createImageBitmap !== 'function' || typeof document === 'undefined') {
    return file;
  }

  let bitmap;
  try {
    bitmap = await createImageBitmap(file);

    const scale = Math.min(1, maxDimension / Math.max(bitmap.width, bitmap.height));
    const width = Math.max(1, Math.round(bitmap.width * scale));
    const height = Math.max(1, Math.round(bitmap.height * scale));
    const canvas = document.createElement('canvas');
    canvas.width = width;
    canvas.height = height;

    const context = canvas.getContext('2d');
    if (!context) return file;

    context.drawImage(bitmap, 0, 0, width, height);

    const blob = await canvasToBlob(canvas, outputType, quality);
    if (!blob || blob.size >= file.size) return file;

    return new File([blob], getOutputName(file.name, outputType), {
      type: outputType,
      lastModified: Date.now(),
    });
  } catch (error) {
    console.warn('Image optimization failed:', error);
    return file;
  } finally {
    bitmap?.close?.();
  }
}
