public ResponseDTO getProductsForOwner (CartRequestDTO cartRequestDTO) throws Exception {
		ResponseDTO responseDTO = new ResponseDTO();
		Long minBudget = 0L;
		Long maxBudget =0L;
		JSONObject budgetJson = null;
		try {
			 responseDTO = bosServiceHelper.getBudgetById(cartRequestDTO.getBudgetId());
			 if(CommonHelper.isEmpty(responseDTO.getData())){
				 return responseDTO;
			 }
			 budgetJson = (JSONObject) responseDTO.getData();
			 minBudget =  Long.parseLong(budgetJson.get("min_budget").toString());
			 maxBudget = Long.parseLong(budgetJson.get("max_budget").toString());

		} catch (JSONException e) {
			logger.info("Couldn't get budget response");
		}

		List<ProductProperty> productProperty = new ArrayList<>();
		if(cartRequestDTO.getPreference().equalsIgnoreCase("R"))
			productProperty = productPropertyRepository.findByResComAndPreferenceAndMinBudgetLessThanEqualAndMaxBudgetGreaterThanEqual(ResCom.valueOf(cartRequestDTO.getResCom()),Preference.valueOf("R_P"),minBudget,maxBudget);
		else
			productProperty = productPropertyRepository.findByResComAndPreferenceAndMinBudgetLessThanEqualAndMaxBudgetGreaterThanEqual(ResCom.valueOf(cartRequestDTO.getResCom()),Preference.valueOf(cartRequestDTO.getPreference()),minBudget,maxBudget);
		if(CommonHelper.isEmpty(productProperty)){
			logger.info("No property property available for given request");
			responseDTO.setBadRequest("No product property available for given request");
			return responseDTO;
		}
		List<BosProduct> bosProductList = bosProductRepository.findByUserClassAndActiveAndOldFlow(cartRequestDTO.getUserClass(),'Y',"N");

		if(CommonHelper.isEmpty(bosProductList)){
			responseDTO.setConflicted("No Data Found for " +CommonHelper.getStringFromObject(cartRequestDTO,logger));
			return responseDTO;
		}
		List<Long> prodIds = bosProductList.stream().map(BosProduct::getProdId).collect(Collectors.toList());
		List<Long> propertyIds = productProperty.stream().map(ProductProperty::getId).collect(Collectors.toList());

		ResponseDTO gstDetailsResponse = getGstData(cartRequestDTO);
		GstDetails gstDetails = new GstDetails();
		if(!CommonHelper.isEmpty(gstDetailsResponse) && !CommonHelper.isEmpty(gstDetailsResponse.getData())){
			gstDetails = (GstDetails) gstDetailsResponse.getData();
		}
		List<Product> productList = productRepository.findByProdIdInAndProductPropertyIdIn(prodIds,propertyIds);
		Map<Long,Product> prodIdVsProduct = productList.stream().collect(Collectors.toMap(Product::getProdId, product -> product));
		bosProductList.removeIf(bosProduct -> !prodIdVsProduct.containsKey(bosProduct.getProdId()));
		final Map<Long,List<BosProduct>> descriptionIdVsBosProduct = bosProductList.stream()
				.collect(Collectors.groupingBy(BosProduct::getProductDescriptionId));
		if(CommonHelper.isEmpty(descriptionIdVsBosProduct)){
			responseDTO.setConflicted("No DescriptionIDs Found for " +CommonHelper.getStringFromObject(bosProductList,logger));
			return responseDTO;
		}
		List<ProductDescription> productDescriptionList = productDescriptionRepository.findByIdIn(descriptionIdVsBosProduct.keySet().stream().collect(Collectors.toList()));

		if(CommonHelper.isEmpty(productDescriptionList)) {
			responseDTO.setConflicted("No data Found for descriptionIDs : " +CommonHelper.getStringFromObject(productDescriptionList,logger));
			return responseDTO;
		}
		final Map<Long,List<BosProduct>> filteredDescriptionIdVsBosProduct = filterDescriptionIds(productDescriptionList,cartRequestDTO,
				descriptionIdVsBosProduct,prodIdVsProduct);
		LandingPageResponse landingPageResponse = bosServiceMediator.formatBosLandingPageResponse(filteredDescriptionIdVsBosProduct);
		landingPageResponse.setLocalityLabel(bosServiceMediator.formatLocalityName(cartRequestDTO.getCityId(),cartRequestDTO.getLocalityId()));
		for (Map.Entry<String, List<ProductCard>> mapEntry : landingPageResponse.getBosSectionMap().entrySet()) {
			List<ProductCard> productCardList = mapEntry.getValue();
			productCardList = productCardList.stream().map(productCard -> {
				return bosServiceMediator.setProductDetails(cartRequestDTO,productCard, filteredDescriptionIdVsBosProduct.get(productCard.getProductDescriptionId()),
										prodIdVsProduct,new ArrayList<>());
			}).collect(Collectors.toList());
			GstDetails finalGstDetails = gstDetails;
			JSONObject finalBudgetJson = budgetJson;
			productCardList.stream().forEach(productCard -> productCard.getProductList().stream().forEach(productDTO -> {
				productDTO.setSellingPrice(Math.floor(productDTO.getSellingPrice()+ ((double) Math.round(productDTO.getSellingPrice() * (finalGstDetails.getTotalTax() / 100) * 10) / 10)));
				try {
					if(finalBudgetJson.has("recommended")){
						String [] recommended = finalBudgetJson.getString("recommended").split(",");
						ArrayList<String> recommendedList = new ArrayList<>(Arrays.asList(recommended));
						if(recommendedList.contains(productDTO.getProdId().toString())){
							for(String recomProductId: recommendedList){
								if(productDTO.getProdId().toString().equalsIgnoreCase(recomProductId)){
									productDTO.setRecommended(true);
								}
							}
							productCard.setRecommended(true);
						}
					}
				} catch (JSONException e) {
					logger.info("Couldn't read json for recommended products");
					e.printStackTrace();
				}
			}));
			if(!CommonHelper.isEmpty(cartRequestDTO.getRecommendation()) && cartRequestDTO.getRecommendation().equals(Boolean.TRUE)) {
				productCardList = productCardList.stream().filter(productCard -> productCard.getRecommended()).collect(Collectors.toList());
			}
			mapEntry.setValue(productCardList);
		}
		Map<String, List<ProductCard>> landingPageBosSectionMap = landingPageResponse.getBosSectionMap();
		List<ProductCard> bosProductCards;
		if(!CommonHelper.isEmpty(landingPageBosSectionMap)){
			for (Map.Entry<String, List<ProductCard>> entry : landingPageBosSectionMap.entrySet()) {
				//System.out.println(entry.getKey() + ":" + entry.getValue());
				entry.setValue(entry.getValue().stream()
						.sorted(Comparator.comparingLong(ProductCard::getProductDescriptionId))
						.collect(Collectors.toList()));
				bosProductCards = landingPageBosSectionMap.get(entry.getKey());
				if(entry.getKey().equals("OFFLINE_OFFERING")) {
					if (!CommonHelper.isEmpty(bosProductCards)) {
						for (ProductCard productCard : bosProductCards) {
							productCard.setProductList(null);
						}
					}
				}
				if (!CommonHelper.isEmpty(bosProductCards)) {
					for (ProductCard productCard : bosProductCards) {
						if (!productCard.getOnlinePurchasable()) {
							productCard.setProductList(null);

						}
						if(cartRequestDTO.getUserClass().equals("A")){
							if(productCard.getProductList() != null){
								for(ProductDTO pDto : productCard.getProductList()){
									try {
										Double p = pDto.getSellingPrice() / pDto.getProdQty();
										pDto.setPricePerUnit(p.intValue() + "/unit");
										pDto.setDiscountPercent(null);
									}catch (Exception e){

									}
								}
							}

						}

					}
				}

			}


		}
		//get Compare Plans
		final Map<String, List<BosProduct>> bosProductMap = bosProductList.stream()
				.collect(Collectors.groupingBy(BosProduct::getProdType));
		final Map<String,List<CompareProduct>> productMap = new HashMap<>();
		for (Map.Entry<String, List<BosProduct>> entry : bosProductMap.entrySet()) {
			List<BosProduct> bosProducts = bosProductMap.get(entry.getKey());
			List<CompareProduct> products = new ArrayList<>();
			for(BosProduct bosProduct : bosProducts){
				Product product = prodIdVsProduct.get(bosProduct.getProdId());
				String validity = "";
				if(!CommonHelper.isEmpty(product.getSubcapDuration()) && product.getSubcapDuration() <61 && product.getSubcapDuration() >31){
					validity+=product.getSubcapDuration().toString()+" days";
				}
				else{
					validity+=CommonHelper.DaysToMonthConvertor(product.getSubcapDuration());
					validity+=validity.equals("1")? " month":" months";
				}



				// abcookie ordering
				if(!CommonHelper.isEmpty(cartRequestDTO.getAbCookie()) && cartRequestDTO.getResCom().equalsIgnoreCase("C") && (bosProduct.getProdType().equalsIgnoreCase("HV")) && ((cartRequestDTO.getAbCookie()<=50 && validity.split(" ")[0].equalsIgnoreCase("6")) || (cartRequestDTO.getAbCookie()>50 && validity.split(" ")[0].equalsIgnoreCase("4")))){
					continue;
				}

				//For App (Temp-Fix)
				if(CommonHelper.isEmpty(cartRequestDTO.getAbCookie()) && cartRequestDTO.getResCom().equalsIgnoreCase("C") && (bosProduct.getProdType().equalsIgnoreCase("HV")) && validity.split(" ")[0].equalsIgnoreCase("6")){
					continue;
				}



				CompareProduct compareProduct = new CompareProduct(validity,product.getProdName(), Long.parseLong(String.valueOf((int)Math.ceil(product.getProdRateRupee()))), product.getProdDuration()+" Months",product.getProdId());
				Double taxAmount = (double) Math.round(product.getProdRateRupee() * (gstDetails.getTotalTax() / 100)*10)/10;
				compareProduct.setSellingPrice( Math.floor(taxAmount+product.getProdRateRupee()));
				if(budgetJson.has("recommended")){
					String [] recommended = budgetJson.getString("recommended").split(",");
					ArrayList<String> recommendedList = new ArrayList<>(Arrays.asList(recommended));
					if(recommendedList.contains(product.getProdId().toString())) {
						for(String recomProductId: recommendedList){
							if(product.getProdId().toString().equalsIgnoreCase(recomProductId)){
								compareProduct.setRecommended(true);
							}
						}
						if(!CommonHelper.isEmpty(cartRequestDTO.getRecommendation()) && cartRequestDTO.getRecommendation().equals(Boolean.TRUE)) {
							products.add(compareProduct);
						}
					}
				}
				if(CommonHelper.isEmpty(cartRequestDTO.getRecommendation()) || cartRequestDTO.getRecommendation().equals(Boolean.FALSE)){
					products.add(compareProduct);
				}
			}
			Collections.sort(products, (CompareProduct compareProduct1 ,CompareProduct compareProduct2)->Integer.valueOf(compareProduct1.getSubCapDuration().split(" ")[0]) - Integer.valueOf(compareProduct2.getSubCapDuration().split(" ")[0]));

			productMap.put(entry.getKey(),products);
		}
		String creteria = budgetJson.getString("id").substring(0,2);
		landingPageResponse.setCompareProducts(bosServiceMediator.buildNewCompareProdTagDetails(productMap,creteria));
		responseDTO.setData(landingPageResponse);
		return responseDTO;
	}
