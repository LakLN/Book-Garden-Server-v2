package com.example.bookgarden.service;

import com.example.bookgarden.constant.OrderStatus;
import com.example.bookgarden.dto.*;
import com.example.bookgarden.entity.*;
import com.example.bookgarden.exception.ForbiddenException;
import com.example.bookgarden.repository.*;
import org.bson.types.ObjectId;
import org.modelmapper.ModelMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
public class OrderService {
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private AddressRepository addressRepository;
    @Autowired
    private BookService bookService;
    @Autowired
    private BookRepository bookRepository;
    @Autowired
    private OrderItemRepository orderItemRepository;
    @Autowired
    private CartItemRepository cartItemRepository;
    @Autowired
    private NotificationService notificationService;
    @Autowired
    private SimpMessagingTemplate messagingTemplate;
    @Value("${client.host}")
    private String clientHost;
    private final ModelMapper modelMapper = new ModelMapper();

    @Transactional
    @Caching(evict = {
            @CacheEvict(value = "orderDTOCache", allEntries = true),
            @CacheEvict(value = "orderItemDTOCache", allEntries = true)
    })
    public ResponseEntity<GenericResponse> createOrder(String userId, CreateOrderRequestDTO createOrderRequestDTO) {
        try {
            Optional<User> optionalUser = userRepository.findById(userId);
            if (optionalUser.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(GenericResponse.builder()
                        .success(false)
                        .message("Người dùng không tồn tại")
                        .data(null)
                        .build());
            }

            User user = optionalUser.get();
            Order order = new Order();
            order.setUser(new ObjectId(userId));
            ModelMapper modelMapper = new ModelMapper();
            modelMapper.map(createOrderRequestDTO, order);

            Address address = findOrCreateAddress(createOrderRequestDTO);
            updateUserAddresses(user, address);

            List<ObjectId> cartItemObjectIds = createOrderRequestDTO.getCartItems().stream()
                    .map(ObjectId::new)
                    .collect(Collectors.toList());

            List<OrderItem> orderItems = processCartItems(cartItemObjectIds);

            order.setOrderItems(orderItems.stream().map(OrderItem::getId).collect(Collectors.toList()));
            order.setStatus("PENDING");
            Order savedOrder = orderRepository.save(order);

            OrderDTO orderDTO = convertToOrderDTO(savedOrder);

            String orderHistoryUrl = clientHost + "/profile/order-history";
            notificationService.createNotification(userId, "Đơn hàng mới", "Đơn hàng của bạn đã được đặt thành công.", orderHistoryUrl, "");

            return ResponseEntity.ok(GenericResponse.builder()
                    .success(true)
                    .message("Đã tạo đơn hàng thành công")
                    .data(orderDTO)
                    .build());

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(GenericResponse.builder()
                    .success(false)
                    .message("Lỗi khi tạo đơn hàng")
                    .data(e.getMessage())
                    .build());
        }
    }
    @Scheduled(fixedRate = 300000) //5 minutes
    @Caching(evict = {
            @CacheEvict(value = "orderDTOCache", allEntries = true),
            @CacheEvict(value = "orderItemDTOCache", allEntries = true)
    })
    public void cancelUnpaidOrders() {
        List<Order> unpaidOrders = orderRepository.findByPaymentMethodAndPaymentStatus("ONLINE", "NOT_PAID");
        for (Order order : unpaidOrders) {
            long orderAgeInMinutes = TimeUnit.MILLISECONDS.toMinutes(new Date().getTime() - order.getOrderDate().getTime());
            if (orderAgeInMinutes >= 30 && "PENDING".equals(order.getStatus().toString())) {
                order.setStatus("CANCELLED");
                orderRepository.save(order);
                notificationService.createNotification(order.getUser().toString(), "Đơn hàng bị hủy", "Đơn hàng của bạn đã bị hủy do không thanh toán thành công trong thời gian quy định.", clientHost + "/profile/order-history", "");
            }
        }
    }

    private Address findOrCreateAddress(CreateOrderRequestDTO createOrderRequestDTO) {
        Optional<Address> optionalAddress = addressRepository.findByNameAndPhoneNumberAndAddress(
                createOrderRequestDTO.getFullName(),
                createOrderRequestDTO.getPhone(),
                createOrderRequestDTO.getAddress());

        if (optionalAddress.isPresent()) {
            return optionalAddress.get();
        } else {
            Address newAddress = new Address();
            newAddress.setAddress(createOrderRequestDTO.getAddress());
            return addressRepository.save(newAddress);
        }
    }

    private void updateUserAddresses(User user, Address address) {
        List<ObjectId> addresses = user.getAddresses();
        if (!addresses.contains(address.getId())) {
            addresses.add(address.getId());
            user.setAddresses(addresses);
            userRepository.save(user);
        }
    }

    private List<OrderItem> processCartItems(List<ObjectId> cartItemObjectIds) {
        List<CartItem> cartItems = cartItemRepository.findAllByIdIn(cartItemObjectIds);
        List<OrderItem> orderItems = new ArrayList<>();

        for (CartItem cartItem : cartItems) {
            OrderItem orderItem = new OrderItem();
            orderItem.setBook(cartItem.getBook());
            orderItem.setQuantity(cartItem.getQuantity());

            Book book = bookRepository.findById(cartItem.getBook()).orElseThrow(() -> new RuntimeException("Sách không tồn tại"));
            book.setSoldQuantity(book.getSoldQuantity() + cartItem.getQuantity());
            book.setStock(book.getStock() - cartItem.getQuantity());
            bookRepository.save(book);

            orderItems.add(orderItemRepository.save(orderItem));
        }

        cartItemRepository.deleteAll(cartItems);
        return orderItems;
    }

    public ResponseEntity<GenericResponse> getUserOrders(String userId) {
        try {
            Optional<User> optionalUser = userRepository.findById(userId);
            if (optionalUser.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(GenericResponse.builder()
                        .success(false)
                        .message("Người dùng không tồn tại")
                        .data(null)
                        .build());
            }
            List<Order> orders = orderRepository.findByUser(new ObjectId(userId));
            List<OrderDTO> orderDTOs = orders.stream()
                    .map(this::convertToOrderDTO)
                    .collect(Collectors.toList());

            return ResponseEntity.ok(GenericResponse.builder()
                    .success(true)
                    .message("Lấy danh sách đơn hàng thành công")
                    .data(orderDTOs)
                    .build());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(GenericResponse.builder()
                    .success(false)
                    .message("Lỗi khi lấy danh sách đơn hàng")
                    .data(e.getMessage())
                    .build());
        }
    }
    @Transactional
    @Caching(evict = {
            @CacheEvict(value = "orderDTOCache", allEntries = true),
            @CacheEvict(value = "orderItemDTOCache", allEntries = true)
    })
    public ResponseEntity<GenericResponse> confirmOrderReceived(String userId, String orderId) {
        try {
            Optional<Order> optionalOrder = orderRepository.findById(new ObjectId(orderId));
            if (optionalOrder.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(GenericResponse.builder()
                        .success(false)
                        .message("Không tìm thấy đơn hàng")
                        .data(null)
                        .build());
            }

            Order order = optionalOrder.get();
            if (!order.getUser().equals(new ObjectId(userId))) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(GenericResponse.builder()
                        .success(false)
                        .message("Bạn không có quyền xác nhận đơn hàng này")
                        .data(null)
                        .build());
            }

            if (!OrderStatus.fromString(order.getStatus().toString()).equals(OrderStatus.DELIVERED)) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(GenericResponse.builder()
                        .success(false)
                        .message("Chỉ có thể xác nhận đơn hàng đã được giao")
                        .data(null)
                        .build());
            }

            order.setStatus(OrderStatus.CONFIRMED.toString());
            Order updatedOrder = orderRepository.save(order);
            OrderDTO orderDTO = convertToOrderDTO(updatedOrder);

            return ResponseEntity.ok(GenericResponse.builder()
                    .success(true)
                    .message("Xác nhận đơn hàng thành công")
                    .data(orderDTO)
                    .build());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(GenericResponse.builder()
                    .success(false)
                    .message("Lỗi khi xác nhận đơn hàng")
                    .data(e.getMessage())
                    .build());
        }
    }


    @Scheduled(fixedRate = 86400000) // 24 giờ
    @Caching(evict = {
            @CacheEvict(value = "orderDTOCache", allEntries = true),
            @CacheEvict(value = "orderItemDTOCache", allEntries = true)
    })
    public void autoConfirmDeliveredOrders() {
        List<Order> deliveredOrders = orderRepository.findByPaymentStatus(OrderStatus.DELIVERED.toString());
        Date now = new Date();
        for (Order order : deliveredOrders) {
            long diffInMillies = Math.abs(now.getTime() - order.getOrderDate().getTime());
            long diff = TimeUnit.DAYS.convert(diffInMillies, TimeUnit.MILLISECONDS);
            if (diff >= 7) { // Nếu đơn hàng đã được giao trong 7 ngày
                order.setStatus(OrderStatus.CONFIRMED.toString());
                orderRepository.save(order);
                notificationService.createNotification(order.getUser().toString(), "Đơn hàng tự động xác nhận", "Đơn hàng của bạn đã được tự động xác nhận sau 7 ngày giao hàng.", clientHost + "/profile/order-history", "");
            }
        }
    }
    @Cacheable("orderDTOCache")
    public ResponseEntity<GenericResponse> getAllOrders(String userId, int page, int size) {
        try {
            Optional<User> optionalUser = userRepository.findById(userId);
            if (optionalUser.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(GenericResponse.builder()
                        .success(false)
                        .message("Không tìm thấy người dùng")
                        .data(null)
                        .build());
            }

            Page<Order> ordersPage;
            Sort sortByOrderDateDesc = Sort.by(Sort.Direction.DESC, "orderDate");
            Pageable pageable = PageRequest.of(page, size, sortByOrderDateDesc);

            if ("Admin".equals(optionalUser.get().getRole()) || "Manager".equals(optionalUser.get().getRole())) {
                ordersPage = orderRepository.findAll(pageable);
            } else {
                ordersPage = orderRepository.findAllByUser(new ObjectId(userId), pageable);
            }

            List<OrderDTO> orderDTOs = ordersPage.stream()
                    .map(this::convertToOrderDTO)
                    .collect(Collectors.toList());

            PageResponse<OrderDTO> response = new PageResponse<>();
            response.setContent(orderDTOs);
            response.setTotalPages(ordersPage.getTotalPages());
            response.setTotalElements(ordersPage.getTotalElements());

            return ResponseEntity.ok(GenericResponse.builder()
                    .success(true)
                    .message("Lấy danh sách đơn hàng thành công")
                    .data(response)
                    .build());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(GenericResponse.builder()
                    .success(false)
                    .message("Lỗi khi lấy danh sách đơn hàng")
                    .data(e.getMessage())
                    .build());
        }
    }
    @Cacheable("orderDTOCache")
    public ResponseEntity<GenericResponse> getAllOrdersWithoutPaging(String userId) {
        try {
            Optional<User> optionalUser = userRepository.findById(userId);
            if (optionalUser.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(GenericResponse.builder()
                        .success(false)
                        .message("Không tìm thấy người dùng")
                        .data(null)
                        .build());
            }

            List<Order> orders;
            Sort sortByOrderDateDesc = Sort.by(Sort.Direction.DESC, "orderDate");

            if ("Admin".equals(optionalUser.get().getRole()) || "Manager".equals(optionalUser.get().getRole())) {
                orders = orderRepository.findAll(sortByOrderDateDesc);
            } else {
                orders = orderRepository.findAllByUser(new ObjectId(userId), sortByOrderDateDesc);
            }

            List<OrderDTO> orderDTOs = orders.stream()
                    .map(this::convertToOrderDTO)
                    .collect(Collectors.toList());

            return ResponseEntity.ok(GenericResponse.builder()
                    .success(true)
                    .message("Lấy danh sách đơn hàng thành công")
                    .data(orderDTOs)
                    .build());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(GenericResponse.builder()
                    .success(false)
                    .message("Lỗi khi lấy danh sách đơn hàng")
                    .data(e.getMessage())
                    .build());
        }
    }
    @Cacheable("orderDTOCache")
    public ResponseEntity<GenericResponse> getOrderById(String userId, String orderId) {
        try {
            Optional<User> optionalUser = userRepository.findById(userId);
            if (optionalUser.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(GenericResponse.builder()
                        .success(false)
                        .message("Không tìm thấy người dùng")
                        .data(null)
                        .build());
            }
            Optional<Order> optionalOrder = orderRepository.findById(new ObjectId(orderId));
            if (optionalOrder.isEmpty()){
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(GenericResponse.builder()
                        .success(false)
                        .message("Không tìm thấy thông tin đơn hàng")
                        .data(null)
                        .build());
            }
            Order order = optionalOrder.get();
            if (!"Admin".equals(optionalUser.get().getRole()) && !"Manager".equals(optionalUser.get().getRole()) && !order.getUser().equals(new ObjectId(userId))) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(GenericResponse.builder()
                        .success(false)
                        .message("Bạn không có quyền truy cập đơn hàng này")
                        .data(null)
                        .build());
            }
            OrderDTO orderDTO = convertToOrderDTO(optionalOrder.get());
            return ResponseEntity.ok(GenericResponse.builder()
                    .success(true)
                    .message("Lấy thông tin đơn hàng thành công")
                    .data(orderDTO)
                    .build());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(GenericResponse.builder()
                    .success(false)
                    .message("Lỗi khi lấy chi tiết đơn hàng")
                    .data(e.getMessage())
                    .build());
        }
    }
    @Transactional
    @Caching(evict = {
            @CacheEvict(value = "orderDTOCache", allEntries = true),
            @CacheEvict(value = "orderItemDTOCache", allEntries = true)
    })
    public ResponseEntity<GenericResponse> updateOrderStatus(String userId, String orderId, UpdateOrderStatusRequestDTO updateOrderStatusRequestDTO) {
        try {
            checkAdminAndManagerPermission(userId);
            Optional<Order> optionalOrder = orderRepository.findById(new ObjectId(orderId));
            if (optionalOrder.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(GenericResponse.builder()
                        .success(false)
                        .message("Không tìm thấy thông tin đơn hàng")
                        .data(null)
                        .build());
            }
            Order order = optionalOrder.get();
            OrderStatus newStatus = OrderStatus.fromString(updateOrderStatusRequestDTO.getStatus());

            if (!canUpdateOrderStatus(order.getStatus(), newStatus)) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(GenericResponse.builder()
                        .success(false)
                        .message("Không thể thay đổi trạng thái đơn hàng")
                        .data(null)
                        .build());
            }
            order.setStatus(updateOrderStatusRequestDTO.getStatus());
            if (updateOrderStatusRequestDTO.getStatus().equals("DELIVERED")){
                order.setPaymentStatus("PAID");
            }
            Order updatedOrder = orderRepository.save(order);
            OrderDTO orderDTO = convertToOrderDTO(updatedOrder);

            String notificationMessage = "Trạng thái đơn hàng của bạn đã được cập nhật thành " + updateOrderStatusRequestDTO.getStatus();
            Notification notification = notificationService.createNotification(order.getUser().toString(), "Cập nhật đơn hàng", notificationMessage, clientHost + "/profile/order-history", "");
            messagingTemplate.convertAndSend("/topic/notifications/" + order.getUser().toString(), notification);

            return ResponseEntity.ok(GenericResponse.builder()
                    .success(true)
                    .message("Cập nhật trạng thái đơn hàng thành công")
                    .data(orderDTO)
                    .build());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(GenericResponse.builder()
                    .success(false)
                    .message("Lỗi khi cập nhật trạng thái đơn hàng")
                    .data(e.getMessage())
                    .build());
        }
    }
    @Transactional
    @Caching(evict = {
            @CacheEvict(value = "orderDTOCache", allEntries = true),
            @CacheEvict(value = "orderItemDTOCache", allEntries = true)
    })
    public ResponseEntity<GenericResponse> handlePaymentCallback(PaymentCallBackRequestDTO paymentCallBackRequestDTO){
        try {
            Optional<Order> optionalOrder = orderRepository.findById(new ObjectId(paymentCallBackRequestDTO.getOrderId()));
            if (optionalOrder.isPresent()) {
                Order order = optionalOrder.get();
                if ("00".equals(paymentCallBackRequestDTO.getResponseCode())) {
                    order.setPaymentStatus("PAID");
                    order.setPaymentDate(new Date());
                    order.setStatus("PROCESSING");
                    order = orderRepository.save(order);
                    OrderDTO orderDTO = convertToOrderDTO(order);

                    return ResponseEntity.ok(GenericResponse.builder()
                            .success(true)
                            .message("Thanh toán thành công")
                            .data(orderDTO)
                            .build());
                } else {
                    return ResponseEntity.ok(GenericResponse.builder()
                            .success(false)
                            .message("Thanh toán thất bại")
                            .build());
                }
            } else {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(GenericResponse.builder()
                        .success(false)
                        .message("Không tìm thấy đơn hàng")
                        .build());
            }
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(GenericResponse.builder()
                    .success(false)
                    .message("Lỗi khi xử lý thanh toán")
                    .data(e.getMessage())
                    .build());
        }
    }
    @Transactional
    @Caching(evict = {
            @CacheEvict(value = "orderDTOCache", allEntries = true),
            @CacheEvict(value = "orderItemDTOCache", allEntries = true)
    })
    public ResponseEntity<GenericResponse> cancelOrder(String userId, String orderId) {
        try {
            Optional<User> optionalUser = userRepository.findById(userId);
            Optional<Order> optionalOrder = orderRepository.findById(new ObjectId(orderId));

            if (optionalUser.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(GenericResponse.builder()
                        .success(false)
                        .message("Người dùng không tồn tại")
                        .data(null)
                        .build());
            }

            if (optionalOrder.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(GenericResponse.builder()
                        .success(false)
                        .message("Không tìm thấy đơn hàng")
                        .data(null)
                        .build());
            }

            Order order = optionalOrder.get();

            if (!order.getUser().equals(new ObjectId(userId))) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(GenericResponse.builder()
                        .success(false)
                        .message("Bạn chỉ có thể thao tác trên đơn hàng của mình")
                        .data(null)
                        .build());
            }

            if (!order.getStatus().toString().equals("PENDING")){
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(GenericResponse.builder()
                        .success(false)
                        .message("Bạn chỉ có thể hủy đơn hàng chưa được xác nhận")
                        .data(null)
                        .build());
            }
            order.setStatus("CANCELLED");
            order = orderRepository.save(order);
            OrderDTO orderDTO = convertToOrderDTO(order);

            return ResponseEntity.status(HttpStatus.OK).body(GenericResponse.builder()
                    .success(true)
                    .message("Hủy đơn hàng thành công")
                    .data(orderDTO)
                    .build());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(GenericResponse.builder()
                    .success(false)
                    .message("Lỗi khi hủy đơn hàng: " + e.getMessage())
                    .data(null)
                    .build());
        }
    }

    @Cacheable("orderDTOCache")
    public OrderDTO convertToOrderDTO(Order order) {
        OrderDTO orderDTO = new OrderDTO();
        modelMapper.map(order, orderDTO); // Sử dụng modelMapper chung

        orderDTO.set_id(order.getId().toString());
        orderDTO.setStatus(order.getStatus().toString());

        List<OrderItem> orderItems = orderItemRepository.findByIdIn(order.getOrderItems());
        List<ObjectId> bookIds = orderItems.stream()
                .map(OrderItem::getBook)
                .collect(Collectors.toList());

        Map<ObjectId, Book> booksMap = bookRepository.findAllById(bookIds).stream()
                .collect(Collectors.toMap(Book::getId, book -> book));

        List<OrderItemDTO> orderItemDTOs = orderItems.stream()
                .map(orderItem -> convertToOrderItemDTO(orderItem, booksMap))
                .collect(Collectors.toList());

        orderDTO.setOrderItems(orderItemDTOs);
        return orderDTO;
    }

    @Cacheable("orderItemDTOCache")
    public OrderItemDTO convertToOrderItemDTO(OrderItem orderItem, Map<ObjectId, Book> booksMap) {
        OrderItemDTO orderItemDTO = new OrderItemDTO();
        modelMapper.map(orderItem, orderItemDTO);

        orderItemDTO.set_id(orderItem.getId().toString());

        Book book = booksMap.get(orderItem.getBook());
        orderItemDTO.setBook(bookService.convertToBookDTO(book));
        return orderItemDTO;
    }
    private boolean canUpdateOrderStatus(OrderStatus currentStatus, OrderStatus newStatus) {
        switch (currentStatus) {
            case PENDING:
                return newStatus == OrderStatus.PROCESSING || newStatus == OrderStatus.CANCELLED;
            case PROCESSING:
                return newStatus == OrderStatus.DELIVERING || newStatus == OrderStatus.CANCELLED;
            case DELIVERING:
                return newStatus == OrderStatus.DELIVERED || newStatus == OrderStatus.CANCELLED;
            case DELIVERED:
                return false;
            case CANCELLED:
                return false;
            default:
                return false;
        }
    }
    private void checkAdminAndManagerPermission(String userId) throws ForbiddenException {
        Optional<User> optionalUser = userRepository.findById(userId);
        if (optionalUser.isPresent()) {
            if (!"Admin".equals(optionalUser.get().getRole()) && !"Manager".equals(optionalUser.get().getRole())) {
                throw new ForbiddenException("Bạn không có quyền thực hiện thao tác này");
            }
        } else {
            throw new ForbiddenException("Người dùng không tồn tại");
        }
    }

    public List<CustomerOrderCountDTO> getTopCustomers() {
        List<CustomerOrderCount> topCustomers = orderRepository.findTopCustomers();
        return topCustomers.stream().map(this::convertToDTO).collect(Collectors.toList());
    }

    private CustomerOrderCountDTO convertToDTO(CustomerOrderCount customerOrderCount) {
        CustomerOrderCountDTO dto = new CustomerOrderCountDTO();
        dto.setUserId(customerOrderCount.getUserId().toString());
        dto.setOrderCount(customerOrderCount.getOrderCount());

        userRepository.findById(customerOrderCount.getUserId().toString()).ifPresent(user -> {
            dto.setFullName(user.getFullName());
            dto.setEmail(user.getEmail());
            dto.setAvatar(user.getAvatar());
        });
        return dto;
    }
}
