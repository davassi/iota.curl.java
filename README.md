
Porting of IOTA CURL in Java.

To execute:

	final IotaCurlMiner iotacurl = new IotaCurlMiner();
        String mined = iotacurl.doCurlPowSingleThread(in, 5);
	System.err.println("Mined: " + mined);

where "in" is a 2673 String character lenght composed by [A-Z] and '9'
	
	
	
