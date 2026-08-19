import {
  S3Client,
  ListObjectsV2Command,
  GetObjectCommand,
  PutObjectCommand,
  DeleteObjectCommand,
  DeleteObjectsCommand,
  HeadBucketCommand,
} from '@aws-sdk/client-s3';
import { config } from './config.mjs';

export class S3Helper {
  constructor(options = {}) {
    this.bucket = options.bucket || config.s3.bucket;
    this.client = new S3Client({
      endpoint: options.endpoint || config.s3.endpoint,
      region: options.region || config.s3.region,
      credentials: {
        accessKeyId: options.accessKeyId || config.s3.accessKeyId,
        secretAccessKey: options.secretAccessKey || config.s3.secretAccessKey,
      },
      forcePathStyle: true,
    });
  }

  async checkHealth() {
    try {
      await this.client.send(new HeadBucketCommand({ Bucket: this.bucket }));
      return true;
    } catch {
      return false;
    }
  }

  async listObjects(prefix = '') {
    const res = await this.client.send(
      new ListObjectsV2Command({
        Bucket: this.bucket,
        Prefix: prefix,
      })
    );
    return res.Contents || [];
  }

  async getObject(key) {
    const res = await this.client.send(
      new GetObjectCommand({
        Bucket: this.bucket,
        Key: key,
      })
    );
    if (res.Body && typeof res.Body.transformToString === 'function') {
      return await res.Body.transformToString();
    }
    return res.Body;
  }

  async putObject(key, body) {
    return await this.client.send(
      new PutObjectCommand({
        Bucket: this.bucket,
        Key: key,
        Body: body,
      })
    );
  }

  async deleteObject(key) {
    return await this.client.send(
      new DeleteObjectCommand({
        Bucket: this.bucket,
        Key: key,
      })
    );
  }

  async clearBucket() {
    const objects = await this.listObjects();
    if (objects.length === 0) return;
    await this.client.send(
      new DeleteObjectsCommand({
        Bucket: this.bucket,
        Delete: { Objects: objects.map((o) => ({ Key: o.Key })) },
      })
    );
  }
}

export { S3Helper as S3Client };
export default S3Helper;
