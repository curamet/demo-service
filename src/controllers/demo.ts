/// <reference path="../types/custom.d.ts" />
import express from 'express';
import logger from '../utils/logger';
import { castToObject } from '../utils/errors';
import { FAILURE, SUCCESS } from '../utils/constants';
import { failure, success } from '../utils/apiResponse';
import { getSecret } from '../utils/secret';
import config from '../config';
import { uploadFileToBucket } from '../utils/gcs';
import { generateRandomString } from '../utils/helpers';
import * as fs from 'fs';
import * as path from 'path';

/**
 * endpoint triggered by a pubsub push subscription
 * @param req
 * @param res
 */
export const pushTrigger = async (req: express.Request, res: express.Response) => {
  try {
    const requestId = req.id || '';
    const metadata = { requestId: requestId };
    logger.info('push subscription has triggered me', metadata);

    logger.info('data from pub/sub', { ...metadata, data: req.body.data });

    logger.info('secret value: ' + (await getSecret(config.secretName)), metadata);

    return res.status(500).send({ error: 'Simulated failure for dead-letter test' });

    // Generate a random file name and random content
    const randomFileName = `${generateRandomString(6)}.txt`;
    const randomContent = generateRandomString(50);

    // Write the random content to the file
    const filePath = path.join(__dirname, randomFileName);
    fs.writeFileSync(filePath, randomContent);
    logger.info(`file created at ${filePath}`, metadata);

    await uploadFileToBucket(filePath, config.bucketName, randomFileName, requestId);

    // do something with the data

    return res.status(200).send(success(`${SUCCESS}: Done`, { ...metadata }));
  } catch (e: any) {
    const errorObject = castToObject(e);
    const metadata = { requestId: req.id, errorObject };
    return res.status(500).send(failure(FAILURE, metadata, 500));
  }
};

/**
 * endpoint triggered when a file is uploaded to the bucket
 * @param req
 * @param res
 */
export const objectNotification = async (req: express.Request, res: express.Response) => {
  try {
    const requestId = req.id || '';
    const metadata = { requestId: requestId };
    logger.info('object notification triggered me', metadata);

    logger.info('file information:', { ...metadata, data: req.body.data });

    // do something with the file information

    return res.status(200).send(success(`${SUCCESS}: Done`, { ...metadata }));
  } catch (e: any) {
    const errorObject = castToObject(e);
    const metadata = { requestId: req.id, errorObject };
    return res.status(500).send(failure(FAILURE, metadata, 500));
  }
};
