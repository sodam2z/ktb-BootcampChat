import { optimizeImageFile } from '../images/optimizeImageFile';

export async function optimizeProfileImage(file, options = {}) {
  return optimizeImageFile(file, {
    maxDimension: 512,
    quality: 0.82,
    ...options,
  });
}
