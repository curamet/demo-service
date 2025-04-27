import r from 'express';
import { pushTrigger, objectNotification } from '../controllers/demo';
import pubsubBodyParser from "../middlewares/pubsubBodyParser";
const router = r.Router();


router.post('/push-trigger', [pubsubBodyParser] , pushTrigger);

router.post('/object-notification', [pubsubBodyParser] , objectNotification);

export default router;
